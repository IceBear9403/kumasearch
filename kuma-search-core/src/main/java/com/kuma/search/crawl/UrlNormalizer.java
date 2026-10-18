package com.kuma.search.crawl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * URL 归一化：把「指向同一个页面的多种写法」收敛成一个字符串。
 *
 * <p><b>这是采集器的第一个关口，不做这一步后面全是白费。</b>
 * 同一个页面在真实站点上会以至少这些形式出现：
 * <pre>
 *   HTTP://Example.COM:80/a/b/../b?b=2&amp;a=1#frag
 *   https://example.com/a/b?a=1&amp;b=2&amp;utm_source=weixin
 *   https://example.com/a/b/?a=1&amp;b=2
 *   https://example.com/a/b?a=1&amp;b=2&amp;PHPSESSID=abc123
 * </pre>
 * 不归一化的话它们是 4 个不同的 {@code url_hash}，会被各抓一遍、各占一份页数配额，
 * 更糟的是 ES 里会出现 4 条同内容文档，搜索结果前四条全是它。
 *
 * <p>归一化规则（顺序即执行顺序）：
 * <ol>
 *   <li>相对路径按 base 解析为绝对 URL —— 页面里的链接大多是相对的；</li>
 *   <li>scheme 与 host 转小写（host 大小写不敏感，但字符串比较敏感）；</li>
 *   <li>去掉默认端口（{@code http:80} / {@code https:443}）；</li>
 *   <li>去掉 fragment（{@code #} 之后的部分不会发给服务端，纯客户端行为）；</li>
 *   <li>去掉跟踪参数与会话 ID 参数；</li>
 *   <li>剩余查询参数按 key 排序；</li>
 *   <li>路径折叠重复斜杠、解析 {@code .} 与 {@code ..}；</li>
 *   <li>非根路径去掉结尾斜杠。</li>
 * </ol>
 *
 * <p><b>刻意不做的两件事：</b>
 * <ul>
 *   <li>不改动 path 的大小写 —— 路径在多数服务端是大小写敏感的，
 *       {@code /News} 与 {@code /news} 可能是两个页面。只有 host 能安全地转小写。</li>
 *   <li>不做百分号编码的规范化（如把 {@code %7E} 还原成 {@code ~}）——
 *       不同服务端对编码的处理并不一致，替它们做决定会引入「归一化后反而 404」的问题。</li>
 * </ul>
 */
@Slf4j
@Component
public class UrlNormalizer {

    /** 无价值的协议：邮件、电话、JS 伪协议。采集器遇到它们应当直接跳过。 */
    private static final Set<String> SKIPPED_SCHEMES = Set.of("mailto", "tel", "javascript", "data", "ftp", "file");

    /**
     * 跟踪参数的前缀。以这些前缀开头的参数一律丢弃。
     *
     * <p>用前缀而非枚举，是因为这类参数由各家分析平台自行发明且不断增加
     * （{@code utm_*}、{@code spm_*}、{@code hmsr}…），枚举追不上。
     */
    private static final List<String> TRACKING_PREFIXES = List.of(
            "utm_", "spm", "hmsr", "hmpl", "hmcu", "hmkw", "hmci");

    /**
     * 精确匹配的跟踪/来源参数。
     *
     * <p>{@code from} 与 {@code ref} 在这里是安全的：它们作为**查询参数**出现时
     * 几乎总是来源标记。如果某个站真的用 {@code from} 承载业务语义（如分页起点），
     * 那它的分页本身就会有其它参数区分页面，去重不会造成漏采。
     */
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "from", "ref", "referer", "referrer", "source", "src",
            "share", "share_source", "share_medium", "share_token", "share_from",
            "fbclid", "gclid", "msclkid", "yclid", "dclid", "igshid",
            "spm", "scm", "wxopenid", "wechat_redirect", "scene");

    /** 会话 ID 类参数。带上它们会让「同一页面 + 不同会话」变成不同 URL。 */
    private static final Set<String> SESSION_PARAMS = Set.of(
            "jsessionid", "phpsessid", "sid", "sessionid", "session_id", "aspsessionid", "cfid", "cftoken");

    /**
     * 明显不含可读正文的扩展名。
     *
     * <p>过滤掉它们不是省流量，而是省**审核队列**：审核是人工的，
     * 一份 PDF 或一张图进了队列就得有人点开看，而它几乎不可能是有检索价值的网页内容。
     */
    private static final Set<String> SKIPPED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg", ".ico",
            ".css", ".js", ".json", ".xml", ".rss",
            ".zip", ".rar", ".7z", ".gz", ".tar", ".exe", ".dmg", ".apk", ".ipa",
            ".mp3", ".mp4", ".avi", ".mov", ".mkv", ".flv", ".wav",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx");

    /**
     * 归一化一个 URL。
     *
     * @param raw      原始 URL，可以是相对路径
     * @param baseUrl  解析相对路径所依据的页面 URL；{@code raw} 已是绝对 URL 时可为 null
     * @return 归一化后的绝对 URL；URL 不可用（协议不支持、无法解析、或是不含正文的静态资源）时返回 null
     */
    public String normalize(String raw, String baseUrl) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();

        URI uri;
        try {
            URI rawUri = new URI(trimmed);
            uri = rawUri.isAbsolute() || baseUrl == null ? rawUri : new URI(baseUrl).resolve(rawUri);
        } catch (URISyntaxException | IllegalArgumentException e) {
            // 解析失败是常态而非异常：页面里混着没转义的空格、中文、残缺模板的情况非常多。
            // 记 DEBUG 就够 —— 每个坏链接都打 WARN 会把日志淹掉，反而看不见真正的问题。
            log.debug("URL 无法解析，已跳过: raw={} base={} err={}", raw, baseUrl, e.getMessage());
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            // ftp/file/data 等一律不采。file 尤其危险：它能读到采集进程的本地文件系统。
            if (SKIPPED_SCHEMES.contains(scheme)) {
                return null;
            }
            return null;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // 形如 https:///path 的畸形 URL，没有 host 就没有站点归属，采了也无从展示
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);

        int port = uri.getPort();
        if (port == 80 || port == 443 || port == -1) {
            port = -1;
        }

        String path = normalizePath(uri.getPath());
        if (isStaticResource(path)) {
            return null;
        }

        String query = normalizeQuery(uri.getRawQuery());

        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (port != -1) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (query != null && !query.isEmpty()) {
            sb.append('?').append(query);
        }
        // fragment 在这里被丢弃 —— 不复原，因为服务端根本收不到它
        return sb.toString();
    }

    /**
     * 路径归一化：折叠重复斜杠、解析 {@code .} 与 {@code ..}、非根路径去尾斜杠。
     *
     * <p>空路径补成 {@code /} 而不是留空：{@code https://example.com} 与
     * {@code https://example.com/} 必须是同一个东西，否则种子入口会被采两遍。
     */
    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        String path = rawPath;
        // 借 URI 处理 . 与 .. —— 自己写这部分的边界情况（/a/.. 与 ../ 的组合）很容易出错
        try {
            path = new URI(null, null, path, null).normalize().getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
        } catch (URISyntaxException e) {
            // 归一化失败就退回原路径：宁可多抓一次，也不要因为这里异常而整页放弃
            log.debug("路径归一化失败，使用原路径: {}", rawPath);
        }
        // 折叠重复斜杠。放在 URI.normalize 之后，因为后者不会处理 //
        while (path.contains("//")) {
            path = path.replace("//", "/");
        }
        // 非根路径去掉结尾斜杠：/a/b/ 与 /a/b 在绝大多数站点是同一个页面
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * 查询串归一化：丢弃跟踪/会话参数，剩余项按 key 排序。
     *
     * <p>排序保证「参数顺序不同」不会产生两个 url_hash。参数内部的编码保持原样，
     * 只对 key 做解码比较，避免 {@code %41} 与 {@code A} 被当成两个 key。
     *
     * @return 归一化后的查询串；参数全被丢弃时返回 null
     */
    private String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (isTrackingParam(lowerKey) || SESSION_PARAMS.contains(lowerKey)) {
                continue;
            }
            kept.add(pair);
        }
        if (kept.isEmpty()) {
            return null;
        }
        Collections.sort(kept);
        return String.join("&", kept);
    }

    private boolean isTrackingParam(String lowerKey) {
        if (TRACKING_PARAMS.contains(lowerKey)) {
            return true;
        }
        for (String prefix : TRACKING_PREFIXES) {
            if (lowerKey.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 按扩展名判断是否为明显不含正文的静态资源。 */
    private boolean isStaticResource(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < path.lastIndexOf('/')) {
            return false;
        }
        String ext = path.substring(dot).toLowerCase(Locale.ROOT);
        return SKIPPED_EXTENSIONS.contains(ext);
    }

    /**
     * 计算归一化 URL 的 MD5，即 {@code t_crawl_url.url_hash} 与
     * {@code t_search_content.biz_id}。
     *
     * <p>用 MD5 而不是 SHA-256：这一列建了唯一索引，长度直接影响索引体积（32 vs 64 字符），
     * 而它面对的威胁是**碰撞**而非**原像**——能故意构造碰撞的攻击者需要先能写种子站配置，
     * 那已经是管理员权限，不需要绕这个弯。正文指纹用 SHA-256 是另一回事，见
     * {@link ContentFingerprint}。
     *
     * @return 32 位小写十六进制；入参为 null 时返回 null
     */
    public String hash(String normalizedUrl) {
        if (normalizedUrl == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(normalizedUrl.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JDK 强制实现的算法，走到这里说明运行环境被篡改过
            throw new IllegalStateException("JDK 缺少 MD5 实现", e);
        }
    }

    /** 从字符串字面量提取 host，失败返回 null。给「只配了域名没配 scheme」的种子站兜底。 */
    public String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = new URI(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * 判断 URL 是否属于该种子站允许采集的范围。
     *
     * <p>子域名判定用「后缀匹配 + 点号边界」而不是 {@code endsWith(domain)}：
     * 后者会让 {@code evil-example.com} 通过 {@code example.com} 的检查 ——
     * 这是最经典的域名判断漏洞，在采集器里它的后果是把任意站点都当成可信来源。
     *
     * @param url              归一化后的 URL
     * @param seedDomain       种子域名（小写）
     * @param allowSubdomain   是否允许子域名
     */
    public boolean inScope(String url, String seedDomain, boolean allowSubdomain) {
        String host = hostOf(url);
        if (host == null || seedDomain == null) {
            return false;
        }
        String seed = seedDomain.toLowerCase(Locale.ROOT);
        if (host.equals(seed)) {
            return true;
        }
        if (!allowSubdomain) {
            return false;
        }
        // 必须是 "xxx." + seed 的形式。只有点号边界才能挡住 evil-example.com 这类前缀伪装。
        return host.endsWith("." + seed);
    }

    /**
     * 判断 URL 是否落在路径前缀内。
     *
     * <p>边界同样要注意：前缀 {@code /news} 不应放行 {@code /newsletter}。
     * 因此前缀以 {@code /} 结尾时直接匹配，否则要求下一字符是 {@code /} 或字符串结束。
     *
     * @param pathPrefix 允许的前缀；为 null 或空表示不限
     */
    public boolean matchPathPrefix(String url, String pathPrefix) {
        if (pathPrefix == null || pathPrefix.isBlank()) {
            return true;
        }
        String path;
        try {
            path = new URI(url).getPath();
        } catch (URISyntaxException e) {
            return false;
        }
        if (path == null) {
            return false;
        }
        String prefix = pathPrefix.trim();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (!path.startsWith(prefix)) {
            return false;
        }
        if (prefix.endsWith("/")) {
            return true;
        }
        return path.length() == prefix.length() || path.charAt(prefix.length()) == '/';
    }

    /** 归一化时被丢弃的跟踪参数名单，供文档与后台提示使用。 */
    public List<String> trackingParams() {
        List<String> all = new ArrayList<>(TRACKING_PARAMS);
        Collections.sort(all);
        return all;
    }

    /** 被过滤的静态资源扩展名，供文档使用。 */
    public List<String> skippedExtensions() {
        List<String> all = new ArrayList<>(SKIPPED_EXTENSIONS);
        Collections.sort(all);
        return all;
    }

    /** 供调试：把归一化前后的差异打成一行，便于排查「为什么这个页面没被采到」。 */
    public String describe(String raw, String baseUrl) {
        String normalized = normalize(raw, baseUrl);
        return normalized == null
                ? "SKIP  " + raw
                : "OK    " + raw + "  ->  " + normalized + "  hash=" + hash(normalized);
    }
}
