package com.kuma.search.admin.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 阿里云邮件推送（DirectMail）的 {@link MailSender} 实现。
 *
 * <h3>为什么不用官方 SDK，而是手写 RPC 签名</h3>
 * 官方 SDK（{@code aliyun-java-sdk-dm}）会拖进一整套 {@code aliyun-java-sdk-core}
 * 及其依赖树，其中包含 JSON/XML 解析、重试框架、多个传输实现 ——
 * 而我们只调用<b>一个</b>接口（{@code SingleSendMail}）。
 * 手写签名用的是 JDK 自带的 {@code HttpClient} 与 {@code Mac}，
 * 零新增依赖，也就不需要在离线环境里赌依赖能不能解析（见 {@link MailSender} 的说明）。
 *
 * <p>代价是这段签名逻辑必须与阿里云的规范严格对齐，因此它<b>需要一次真实发信的联调验证</b>，
 * 已登记在 {@code docs/08-roadmap.md} §6.1。这也是本项目其它外部集成的一贯做法：
 * 接口先定好、实现写完整、但明确标注"未经过真实环境验证"。
 *
 * <h3>只在 {@code kuma.auth.email.enabled=true} 且 {@code provider=aliyun} 时装配</h3>
 * 关掉邮箱功能时这个 Bean 根本不存在，{@code EmailCodeService} 也拒绝对外服务，
 * 因此应用仍能正常启动 —— 一个默认关闭的功能不应该因为缺少凭据而让整个应用起不来。
 *
 * <p>两个 {@code @ConditionalOnProperty} 是与 {@link SpringMailSender} 配对的：
 * 同一时刻必须<b>有且只有一个</b> {@link MailSender} 实现存在。
 * 多一个的后果不是"按优先级选一个"，而是 {@code EmailCodeService} 里的
 * {@code ObjectProvider#getIfAvailable()} 抛 {@code NoUniqueBeanDefinitionException}，
 * 表现为启动失败。{@code matchIfMissing=true} 是为了让没配 {@code provider} 的
 * 既有部署继续走 DirectMail，行为不变。
 *
 * <h3>⚠️ AK/SK 只走环境变量</h3>
 * {@code kuma.auth.email.access-key-id} / {@code access-key-secret} 在
 * {@code application.yml} 里必须留空，由 {@code KUMA_DM_AK} / {@code KUMA_DM_SK} 注入，
 * 且<b>只授 DirectMail 发信权限，不要用主账号 AK</b> ——
 * 发信凭据泄露的后果是"别人用你的域名发垃圾邮件"，
 * 而主账号 AK 泄露的后果是整个云账号。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kuma.auth.email", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "kuma.auth.email", name = "provider", havingValue = "aliyun", matchIfMissing = true)
public class AliyunDirectMailSender implements MailSender {

    /** DirectMail 的 RPC 接口地址。 */
    private static final String ENDPOINT = "https://dm.aliyuncs.com/";

    /** RPC API 版本。写死而不是可配：它随接口契约变化，配错了报错信息会非常难懂。 */
    private static final String API_VERSION = "2015-11-23";

    /** 阿里云要求的时间戳格式：ISO8601 UTC，秒级。 */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /** 签名算法标识。 */
    private static final String SIGNATURE_METHOD = "HMAC-SHA1";

    private final KumaSearchProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 发信用的 HTTP 客户端。
     *
     * <p>刻意<b>不</b>设置 {@code followRedirects}：一个发信请求被重定向到别处，
     * 意味着请求体里的 AccessKey 签名会跟着走。这与 {@code HttpPageFetcher}
     * 里不跟随重定向是同一条理由。
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public AliyunDirectMailSender(KumaSearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动时校验凭据是否配齐。
     *
     * <p><b>必须在这里失败，而不是等到用户点"获取验证码"时才失败。</b>
     * 启用了邮箱功能却没配凭据，是一个"部署时就该发现"的配置错误；
     * 让它拖到运行时才暴露，表现是「功能按钮能用但每次都报发送失败」，
     * 而那时候没有人在看启动日志。
     */
    @PostConstruct
    void validateCredentials() {
        KumaSearchProperties.Auth.Email email = properties.getAuth().getEmail();
        if (isBlank(email.getAccessKeyId()) || isBlank(email.getAccessKeySecret())
                || isBlank(email.getAccountName())) {
            throw new IllegalStateException(
                    "kuma.auth.email.enabled=true，但 access-key-id / access-key-secret / account-name "
                            + "未配置完整。这三项必须通过环境变量注入，"
                            + "见 docs/07-deployment.md 的配置项清单。");
        }
    }

    @Override
    public boolean supportsInlineImages() {
        // SingleSendMail 没有内联图片（CID）参数 —— 它的正文里只能放外链图片，
        // 而外链图片在 Gmail / Outlook 默认是不加载的（收件人看到灰框）。
        // 因此上报 false，让 EmailCodeTemplate 渲染成纯文字页头，而不是发出一个必然灰框的图标。
        return false;
    }

    @Override
    public void send(MailMessage message) {
        KumaSearchProperties.Auth.Email email = properties.getAuth().getEmail();

        if (message.getInlineImages() != null && !message.getInlineImages().isEmpty()) {
            // 明确报错而不是静默丢掉：静默丢弃的表现是"邮件发出去了，但页头那个图标是个灰框"，
            // 从日志上完全看不出来。走到这里说明调用方没有按 supportsInlineImages() 的结果来准备正文。
            throw new IllegalStateException(
                    "AliyunDirectMailSender 不支持内联图片，但邮件里带了 "
                            + message.getInlineImages().size() + " 张。调用方应先问 supportsInlineImages()。");
        }

        Map<String, String> params = new TreeMap<>();
        // ---- 公共参数 ----
        params.put("Format", "JSON");
        params.put("Version", API_VERSION);
        params.put("AccessKeyId", email.getAccessKeyId());
        params.put("SignatureMethod", SIGNATURE_METHOD);
        params.put("SignatureVersion", "1.0");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("Timestamp", TIMESTAMP_FORMAT.format(Instant.now()));
        params.put("RegionId", email.getRegionId());
        params.put("Action", "SingleSendMail");
        // ---- 业务参数 ----
        params.put("AccountName", email.getAccountName());
        params.put("ToAddress", message.getToAddress());
        params.put("Subject", message.getSubject());
        params.put("ReplyToAddress", String.valueOf(email.isReplyToAddress()));
        params.put("FromAlias", email.getFromAlias());
        // AddressType=1 表示发信地址是"随机账号"的补充形式；用控制台配好的固定发信地址时取 0。
        params.put("AddressType", "0");
        // 两类正文都带上。DirectMail 对 HtmlBody 缺失的态度随接口版本变化
        // （曾有版本要求 HtmlBody 非空），两个都给就不必赌它的行为。
        params.put("TextBody", message.getTextBody());
        if (message.getHtmlBody() != null) {
            params.put("HtmlBody", message.getHtmlBody());
        }

        params.put("Signature", sign(params, email.getAccessKeySecret()));

        String body = params.entrySet().stream()
                .map(e -> percentEncode(e.getKey()) + "=" + percentEncode(e.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 网络问题不是"用户输入错了"，因此用系统错误而不是参数错误。
            // 日志里不带 AK/SK，也不带完整请求体（body 里有收件人地址）。
            log.error("DirectMail 请求失败（网络层）：to={} err={}", message.getToAddress(), e.getMessage());
            throw BizException.of(ResultCode.SYSTEM_ERROR, "邮件发送失败，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.of(ResultCode.SYSTEM_ERROR, "邮件发送被中断，请稍后重试");
        }

        handleResponse(response, message.getToAddress());
    }

    /**
     * 解析 DirectMail 的响应。
     *
     * <p>DirectMail 的失败也返回 <b>HTTP 200</b>，错误信息在响应体的 {@code Code} 字段里 ——
     * 只判断状态码会把所有失败都当成成功，用户等着一封永远不会到的邮件。
     * 因此成功与否必须以响应体为准。
     */
    private void handleResponse(HttpResponse<String> response, String toAddress) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (IOException e) {
            log.error("DirectMail 响应无法解析：status={} body={}", response.statusCode(), response.body());
            throw BizException.of(ResultCode.SYSTEM_ERROR, "邮件发送失败，请稍后重试");
        }

        String code = root.path("Code").asText("");
        if (response.statusCode() == 200 && code.isEmpty()) {
            // 成功时响应里只有 RequestId，不打日志正文
            log.info("验证码邮件已提交 DirectMail，to={}", toAddress);
            return;
        }

        String message = root.path("Message").asText("未知错误");
        log.error("DirectMail 发送失败：status={} code={} message={} to={}",
                response.statusCode(), code, message, toAddress);

        // 按错误码给出对运维有意义的文案。不把厂商原文直接返回给前端 ——
        // 它可能包含账号名、发信域名等信息，而前端是公开可达的。
        String userMessage = switch (code) {
            // 额度耗尽：这是最可能发生的一种失败，且必须让运维立刻看到
            case "InvalidSendAddress", "SendFrequencyLimit", "QuotaExhausted" ->
                    "邮件服务额度受限，请联系管理员";
            // 凭据问题：部署配置错误，与用户操作无关
            case "InvalidAccessKeyId.NotFound", "SignatureDoesNotMatch", "Forbidden" ->
                    "邮件服务配置有误，请联系管理员";
            default -> "邮件发送失败，请稍后重试";
        };
        throw BizException.of(ResultCode.SYSTEM_ERROR, userMessage);
    }

    /**
     * 按阿里云 RPC 风格（签名版本 1.0）计算签名。
     *
     * <p>步骤是规范规定的，顺序不能变：
     * <ol>
     *   <li>把所有参数按参数名<b>字典序</b>排列（因此上面用 {@link TreeMap}）；</li>
     *   <li>按 {@code key=value} 拼接，逐项做百分号编码；</li>
     *   <li>拼出规范化查询串后再编码一次，与 HTTP 方法、路径一起组成待签串；</li>
     *   <li>用 {@code AccessKeySecret + "&"} 作密钥做 HMAC-SHA1，结果 Base64。</li>
     * </ol>
     *
     * <p>{@code Signature} 本身<b>不参与</b>签名（它此刻还没算出来），
     * 这也是为什么签名要在参数收集完成之后单独做一次。
     */
    private String sign(Map<String, String> params, String accessKeySecret) {
        String canonicalized = params.entrySet().stream()
                .map(e -> percentEncode(e.getKey()) + "=" + percentEncode(e.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");

        String stringToSign = "POST" + "&" + percentEncode("/") + "&" + percentEncode(canonicalized);

        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(
                    (accessKeySecret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // 两者都是 JDK 强制提供的，走到这里说明运行环境异常
            throw new IllegalStateException("计算 DirectMail 签名失败", e);
        }
    }

    /**
     * 阿里云规范的百分号编码。
     *
     * <p>与 {@code URLEncoder} 的默认行为有三处必须修正，这三处错了会导致
     * {@code SignatureDoesNotMatch} 而错误信息完全不提示原因：
     * <ul>
     *   <li>空格：{@code URLEncoder} 编成 {@code +}，阿里云要求 {@code %20}；</li>
     *   <li>星号：{@code URLEncoder} <b>不编码</b> {@code *}，阿里云要求 {@code %2A}；</li>
     *   <li>波浪号：{@code URLEncoder} 编成 {@code %7E}，阿里云要求原样保留。</li>
     * </ul>
     * 中文验证码会经过这里（正文里有"验证码"三个字），因此这段替换不能省。
     */
    private static String percentEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
