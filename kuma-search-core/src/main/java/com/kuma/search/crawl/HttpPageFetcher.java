package com.kuma.search.crawl;

import com.kuma.search.config.KumaSearchProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 JDK {@code HttpClient} 的抓取实现。
 *
 * <p>用 JDK 自带的客户端而不是 OkHttp / Apache HttpClient：需要的功能（超时、
 * 条件请求、重定向策略）它都有，而依赖越少，这个「要长期无人值守运行的组件」
 * 的升级面就越小。少数它不提供的便利（自动解压、连接池调优）在下面各自说明了绕法。
 *
 * <h3>⚠️ 这个类是采集子系统里唯一会「对外发请求」的地方，也是唯一的出网口</h3>
 * 因此全部安全约束都集中在这里，改这个类的时候请把下面每一条都过一遍。
 *
 * <h3>SSRF 防护</h3>
 * 种子站是<b>由管理员配置</b>的，而配置一旦能指向 {@code 127.0.0.1} 或
 * {@code 169.254.169.254}（云厂商的实例元数据服务），这个采集器就变成了
 * 一个能读到自己所在机器与云账号凭证的 SSRF 工具。因此：
 * <ul>
 *   <li>解析出的 IP 若属于环回 / 私有 / 链路本地 / 唯一本地 / 组播 / 未指定地址，一律拒绝；</li>
 *   <li>开关 {@code kuma.crawl.allow-private-hosts} <b>在生产必须为 false</b>，
 *       它只是为了写单测时能指到本地起的测试服务器。</li>
 * </ul>
 *
 * <p><b>残留风险（如实记录，不要以为已经防住了）</b>：这里是「先解析域名、判定 IP、
 * 再让 HttpClient 按域名去连」，两次解析之间的 DNS 结果理论上可以不同
 * （DNS Rebinding 攻击）。彻底堵住需要「自己连 IP 并手动设置 Host 头」，
 * 代价是失去 HttpClient 的连接池与 TLS SNI 处理。本期采集范围是白名单站点、
 * 不加载外部提供的词库以外的可执行内容，判定为可接受，故不改。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpPageFetcher implements PageFetcher {

    /** 正文里声明编码的两种写法，用于响应头没给 charset 时兜底。 */
    private static final Pattern META_CHARSET = Pattern.compile(
            "<meta[^>]+charset\\s*=\\s*[\"']?([a-zA-Z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    /**
     * 只接受这些内容类型。其余（PDF、图片、压缩包、音视频）直接判为「非文本」跳过。
     *
     * <p><b>判的是「文本 / 不是文本」，不是「HTML / 不是 HTML」</b>，这一点是刻意的：
     * 这个类不该知道调用方打算怎么用响应体。种子站的 {@code source_type} 决定了
     * body 会被哪个抽取器解（HTML 用 {@code ContentExtractor}，MediaWiki API 用
     * {@code MediaWikiApiExtractor}），而那是 {@code CrawlPipeline} 的知识。
     * 把「HTML」写进这个口子的名字里，结果是采集器一旦支持第二种来源，
     * 第一批请求就会在这里被全部拦掉，而报错信息说的是"非 HTML 内容" ——
     * 看的人会以为是站点返回错了，不会想到是自己这边不允许 JSON。
     *
     * <p>JSON 这一类是给 MediaWiki 的 {@code api.php} 用的：
     * {@code format=json} 返回 {@code application/json; charset=utf-8}。
     * 同时收下 {@code text/javascript} —— MediaWiki 在部分版本/配置下用它当
     * JSON 的 MIME 类型（历史遗留，为了让 JSONP 与 CORS 生效），
     * 而漏掉它的表现是"某些 wiki 采不到、另一些正常"，非常难查。
     *
     * <p>{@code text/plain} 在名单里是因为不少站点用它返回伪装的 HTML。
     * 它和 JSON 一样，最终都会走过「抽不出标题 → 判定失败」这条正常路径，
     * 所以放宽的代价只是多读一小段文本，不会让垃圾内容进库。
     */
    private static final List<String> TEXT_CONTENT_TYPES = List.of(
            "text/html", "application/xhtml+xml", "text/plain",
            "application/json", "text/javascript");

    /**
     * 编码探测时最多读多少个字节的头部。
     *
     * <p>{@code <meta charset>} 按规范必须出现在前 1024 字节内，取 4096 已足够宽裕。
     * 不设这个上限就得先读完整页再猜编码，等于放弃了「边读边判编码」。
     */
    private static final int CHARSET_SNIFF_BYTES = 4096;

    /**
     * 中文站点的常见遗留编码。
     *
     * <p>只在 HTTP 头与 {@code <meta>} 都没给编码时才尝试它。这些站点数量已经不多，
     * 但一旦遇到，不用它们的结果就是整页乱码进审核队列，而乱码文本<b>不会被敏感词命中</b>
     * —— 这是一条实际的绕过路径，不是体验问题。
     */
    private static final List<String> FALLBACK_CHARSETS = List.of("GB18030", "GBK", "BIG5");

    private final KumaSearchProperties properties;

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        KumaSearchProperties.Crawl crawl = properties.getCrawl();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(crawl.getConnectTimeoutMillis()))
                // NORMAL 会跟随 http→https 与同协议内的重定向，但会拒绝 http→file 之类的降级跳转。
                // 采集场景必须跟随重定向：大量站点的 http 会 301 到 https，不跟随就一个页面也采不到。
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        log.info("采集器 HTTP 客户端已初始化: connectTimeout={}ms readTimeout={}ms maxContent={}KB allowPrivateHosts={}",
                crawl.getConnectTimeoutMillis(), crawl.getReadTimeoutMillis(),
                crawl.getMaxContentBytes() / 1024, crawl.isAllowPrivateHosts());
        if (crawl.isAllowPrivateHosts()) {
            // 这条 WARN 是刻意做成噪音的：它是「生产配置错了」的信号，不该被静默忽略
            log.warn("⚠️ kuma.crawl.allow-private-hosts=true —— 采集器可访问内网地址，"
                    + "该配置仅应用于本地测试，生产环境必须为 false");
        }
    }

    @Override
    public PageFetchResult fetch(String url, String etag, String lastModified) {
        KumaSearchProperties.Crawl crawl = properties.getCrawl();

        if (!isHostAllowed(url, crawl.isAllowPrivateHosts())) {
            // 不是「失败重试」，是「永远不该抓」。返回永久失败让调用方置为跳过状态。
            return PageFetchResult.failure(PageFetchResult.NO_RESPONSE, url,
                    "目标地址属于内网/环回范围，已按 SSRF 防护拒绝", null);
        }

        HttpRequest request;
        try {
            request = buildRequest(url, etag, lastModified, crawl);
        } catch (IllegalArgumentException e) {
            return PageFetchResult.failure(PageFetchResult.NO_RESPONSE, url,
                    "请求构造失败: " + e.getMessage(), null);
        }

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            // 网络类失败（连接被拒、超时、DNS 失败、TLS 握手失败）——可重试
            log.debug("抓取失败: url={} err={}", url, e.toString());
            return PageFetchResult.failure(PageFetchResult.NO_RESPONSE, url, e.toString(), null);
        } catch (InterruptedException e) {
            // 调度器要求停止。必须恢复中断标志，否则上层的关闭流程会卡住
            Thread.currentThread().interrupt();
            return PageFetchResult.failure(PageFetchResult.NO_RESPONSE, url, "抓取被中断", null);
        }

        int status = response.statusCode();
        String finalUrl = response.uri() == null ? url : response.uri().toString();
        String responseEtag = headerFirst(response, "etag");
        String responseLastModified = headerFirst(response, "last-modified");
        Long retryAfter = parseRetryAfter(headerFirst(response, "retry-after"));

        if (status == 304) {
            drain(response);
            return PageFetchResult.notModified(finalUrl);
        }

        if (status < 200 || status >= 300) {
            drain(response);
            String msg = "HTTP " + status
                    + (finalUrl.equals(url) ? "" : "（重定向至 " + finalUrl + "）");
            return PageFetchResult.failure(status, finalUrl, msg, retryAfter);
        }

        String contentType = headerFirst(response, "content-type");
        if (!isTextContentType(contentType)) {
            drain(response);
            return PageFetchResult.failure(status, finalUrl,
                    "非文本内容（不在接受的内容类型里），已跳过: " + contentType, null);
        }

        byte[] raw = readBounded(response, crawl.getMaxContentBytes());
        if (raw == null) {
            // 超过上限。这不是可重试的错误 —— 重试一次还是同样的大小
            return PageFetchResult.failure(status, finalUrl,
                    "响应体超过上限 " + crawl.getMaxContentBytes() + " 字节，已中止", null);
        }
        if (raw.length == 0) {
            return PageFetchResult.failure(status, finalUrl, "响应体为空", null);
        }

        String body = decode(raw, contentType);
        return PageFetchResult.success(status, body, contentType, finalUrl, responseEtag, responseLastModified);
    }

    /**
     * 构造请求。
     *
     * <p>刻意<b>不发送</b> {@code Accept-Encoding: gzip}：JDK 的 HttpClient 不会自动解压，
     * 发了就得自己处理 {@code Content-Encoding} 与 {@code GZIPInputStream}。
     * 不发的代价是多占一些带宽，收益是少一处可能出错的地方。
     * 将来若要开启压缩，必须同时实现解压分支，否则拿到的会是二进制乱码，
     * 而乱码不会命中任何敏感词。
     */
    private HttpRequest buildRequest(String url, String etag, String lastModified,
                                     KumaSearchProperties.Crawl crawl) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(crawl.getReadTimeoutMillis()))
                .GET()
                .header("User-Agent", crawl.getUserAgent())
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5");

        // 条件请求：这是「增量重抓」的第一层，命中 304 就完全不必读正文
        if (etag != null && !etag.isBlank()) {
            builder.header("If-None-Match", etag);
        } else if (lastModified != null && !lastModified.isBlank()) {
            // ETag 优先于 Last-Modified：后者的精度只到秒，同一秒内的两次修改无法区分。
            // 两者都给也可以，但服务端的取舍规则不统一，只用更强的那一个更可预期。
            builder.header("If-Modified-Since", lastModified);
        }
        return builder.build();
    }

    /**
     * 读取响应体，超过上限时返回 null 并关闭流。
     *
     * <p>必须边读边判。{@code BodyHandlers.ofByteArray()} 会把整个响应读进内存后才交给我们，
     * 面对一个超大响应，进程在那之前就已经 OOM 了。
     */
    private byte[] readBounded(HttpResponse<InputStream> response, int maxBytes) {
        try (InputStream in = response.body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    log.warn("响应体超过 {} 字节，中止读取", maxBytes);
                    return null;
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            log.debug("读取响应体失败: {}", e.toString());
            return new byte[0];
        }
    }

    /** 关掉没读的响应体，让连接能回到池里复用。 */
    private void drain(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            in.readNBytes(1);
        } catch (IOException e) {
            log.trace("关闭响应体失败: {}", e.toString());
        }
    }

    /**
     * 决定用哪个编码解码响应体。优先级：HTTP 头 > 页面 {@code <meta>} > GB 系兜底 > UTF-8。
     *
     * <p><b>为什么 GB 系要排在 UTF-8 之前</b>：如果前两层都没给编码，
     * 用 UTF-8 解一份 GBK 的页面不会报错，只会产出满屏替换字符（{@code \uFFFD}）。
     * 而如果那份页面上真有违规内容，替换字符不会被任何敏感词命中 ——
     * 这次采集直接漏过去了，且没有任何异常可查。反过来，用 GB18030 解一份 UTF-8 页面
     * 得到的是「大部分正常、少数汉字错乱」，至少能被敏感词命中一部分。
     * 两害相权，兜底选 GB 系。判断依据是「用某个编码解码后替换字符是否显著减少」。
     */
    private String decode(byte[] raw, String contentType) {
        Charset fromHeader = charsetFromContentType(contentType);
        if (fromHeader != null) {
            return new String(raw, fromHeader);
        }
        if (isJsonContentType(contentType)) {
            // JSON 的编码是规范定死的：RFC 8259 规定 UTF-8，且它**没有**任何声明编码的机制。
            // 所以这里不嗅探、不猜 —— 猜错的代价很大且无声：下面两步都会去找
            // <meta charset=...>，而 JSON 正文里出现这串字面量是可能的
            // （维基条目讲 HTML 时就会），一旦命中，<meta charset=gbk> 之类的值
            // 会让整篇 JSON 按 GBK 解。结果是中文全成乱码，
            // 而乱码的字段既搜不出来，也不会命中任何敏感词 —— 这两个后果都不报错。
            // 对方已经明确说了这是 JSON，再替它猜编码是纯风险、零收益。
            return new String(raw, StandardCharsets.UTF_8);
        }
        Charset fromMeta = charsetFromMeta(raw);
        if (fromMeta != null) {
            return new String(raw, fromMeta);
        }
        for (String name : FALLBACK_CHARSETS) {
            if (!Charset.isSupported(name)) {
                continue;
            }
            String candidate = new String(raw, Charset.forName(name));
            if (!hasReplacementChars(candidate)) {
                return candidate;
            }
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    /**
     * 用替换字符 {@code U+FFFD} 的比例判断「这个编码猜对了没有」。
     *
     * <p>阈值取到 1%（而不是「一个都没有」）：内容里偶尔真的会出现一个替换字符
     * （站点自己就存了坏数据），要求零个会让所有页面都判为猜错。
     */
    private boolean hasReplacementChars(String text) {
        if (text.isEmpty()) {
            return false;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFD') {
                count++;
            }
        }
        return count * 100 > text.length();
    }

    /** 从 {@code Content-Type} 头里取 charset 参数。 */
    private Charset charsetFromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx < 0) {
            return null;
        }
        String name = contentType.substring(idx + "charset=".length()).trim();
        int semi = name.indexOf(';');
        if (semi >= 0) {
            name = name.substring(0, semi);
        }
        name = name.replace("\"", "").replace("'", "").trim();
        return safeCharset(name);
    }

    /**
     * 从 {@code <meta charset>} 里取编码。
     *
     * <p>只用 ASCII 解前 {@value #CHARSET_SNIFF_BYTES} 字节来搜 —— 此时编码还不知道，
     * 拿一个猜的编码去解码再搜 meta，猜错了就搜不到，会形成「越错越搜不到」的循环。
     * ASCII 对所有候选编码都是兼容的，是这里唯一安全的选择。
     */
    private Charset charsetFromMeta(byte[] raw) {
        int len = Math.min(raw.length, CHARSET_SNIFF_BYTES);
        String head = new String(raw, 0, len, StandardCharsets.ISO_8859_1);
        Matcher m = META_CHARSET.matcher(head);
        if (m.find()) {
            return safeCharset(m.group(1));
        }
        return null;
    }

    /** 构造 Charset，不支持的编码名返回 null 而不是抛异常（页面上的 charset 值不可信）。 */
    private Charset safeCharset(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Charset.forName(name.trim());
        } catch (RuntimeException e) {
            log.debug("页面声明了不支持的编码: {}", name);
            return null;
        }
    }

    private boolean isTextContentType(String contentType) {
        if (contentType == null) {
            // 没有 Content-Type 的站点是少数但存在。默认放行，让抽取与长度判定去拦：
            // 一份 PDF 被当成 HTML 抽取的结果必然是「正文过短」而被跳过。
            return true;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        for (String allowed : TEXT_CONTENT_TYPES) {
            if (lower.contains(allowed)) {
                return true;
            }
        }
        return false;
    }

    /** 是否是我们当作 JSON 处理的内容类型。用于 {@link #decode} 里跳过编码嗅探。 */
    private boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("application/json") || lower.contains("text/javascript");
    }

    /**
     * 解析 {@code Retry-After}。
     *
     * <p>两种合法形式都支持：秒数（{@code Retry-After: 120}）与 HTTP 日期
     * （{@code Retry-After: Wed, 21 Oct 2015 07:28:00 GMT}）。只实现前者会漏掉一半的站点，
     * 而漏掉的表现是「我们按自己的退避节奏继续敲对方」。
     *
     * @return 等待秒数；无法解析或已过期时返回 null（表示按本地策略退避）
     */
    private Long parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            long seconds = Long.parseLong(v);
            return seconds < 0 ? null : seconds;
        } catch (NumberFormatException ignored) {
            // 不是秒数，试日期形式
        }
        try {
            ZonedDateTime target = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            long seconds = target.toInstant().getEpochSecond() - java.time.Instant.now().getEpochSecond();
            // 已经过去的日期说明对方的时间戳过期了，按本地策略处理即可
            return seconds <= 0 ? null : seconds;
        } catch (RuntimeException e) {
            log.debug("无法解析 Retry-After: {}", v);
            return null;
        }
    }

    private String headerFirst(HttpResponse<?> response, String name) {
        Optional<String> value = response.headers().firstValue(name);
        return value.orElse(null);
    }

    /**
     * 判定目标主机是否允许抓取（SSRF 防护的第一道，也是主要在起作用的一道）。
     *
     * <p>判定用的是<b>解析后的 IP</b>而不是域名字符串：{@code http://localhost:8080} 与
     * {@code http://127.0.0.1:8080} 与 {@code http://internal.corp}（内网 DNS 解析到 10.x）
     * 是同一件事的三种写法，只检查域名写法必然漏。
     */
    public boolean isHostAllowed(String url, boolean allowPrivateHosts) {
        if (allowPrivateHosts) {
            return true;
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isBlockedAddress(addr)) {
                    log.warn("拒绝抓取内网地址: url={} resolved={}", url, addr.getHostAddress());
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            // 解析不了就当它不存在。返回 false 会让上层记为「永久失败·跳过」，
            // 这正是我们想要的：DNS 里没有的域名重试一万次也不会有。
            log.debug("域名无法解析: {} - {}", host, e.toString());
            return false;
        }
    }

    /**
     * 单个 IP 是否属于「不该被采集器访问」的范围。
     *
     * <p>逐项都要有。<b>最容易漏的是 {@code isLinkLocalAddress}：云厂商的实例元数据服务
     * 就在 {@code 169.254.169.254}</b>，它是 SSRF 攻击的首要目标，而很多人写防护时
     * 只记得 {@code isSiteLocalAddress}（10/172.16/192.168）。
     */
    public boolean isBlockedAddress(InetAddress addr) {
        return addr.isLoopbackAddress()        // 127.0.0.0/8、::1
                || addr.isSiteLocalAddress()   // 10/8、172.16/12、192.168/16
                || addr.isLinkLocalAddress()   // 169.254.0.0/16 ← 云元数据服务在这里
                || addr.isAnyLocalAddress()    // 0.0.0.0
                || addr.isMulticastAddress()
                || isUniqueLocalIpv6(addr)
                || isBlockedIpv4Mapped(addr);
    }

    /** IPv6 唯一本地地址 {@code fc00::/7}。{@code isSiteLocalAddress} 对 IPv6 不覆盖这一段。 */
    private boolean isUniqueLocalIpv6(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /**
     * IPv4-mapped IPv6 地址（{@code ::ffff:a.b.c.d}）。
     *
     * <p>这一段必须单独判，否则是一条现成的绕过路径：{@code http://[::ffff:127.0.0.1]/}
     * 与 {@code http://127.0.0.1/} 指向同一处，但 Java 的 {@code isLoopbackAddress()}
     * 对前者的实现是「不等 ::1 就返回 false」—— 也就是说它会被判成公网地址放行。
     * 判定方式是把后 4 字节还原成 IPv4 再走一遍同一套规则。
     */
    private boolean isBlockedIpv4Mapped(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        if ((bytes[10] & 0xFF) != 0xFF || (bytes[11] & 0xFF) != 0xFF) {
            return false;
        }
        try {
            InetAddress v4 = InetAddress.getByAddress(
                    new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
            return v4.isLoopbackAddress() || v4.isSiteLocalAddress()
                    || v4.isLinkLocalAddress() || v4.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            // 4 字节数组不可能构造失败，保守起见按阻止处理
            return true;
        }
    }
}
