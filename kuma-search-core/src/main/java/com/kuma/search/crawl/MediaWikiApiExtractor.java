package com.kuma.search.crawl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * MediaWiki API 响应的抽取（维基百科 / 维基文库 / 维基词典这一类站点）。
 *
 * <h3>为什么这些站点不走 HTML 抓取，而走 API</h3>
 * 三点，每一点单独都足以决定这件事：
 * <ol>
 *   <li><b>robots 与 HTML 抓取的正当性在这类站点上是拧的。</b>
 *       MediaWiki 的 robots.txt 对 {@code *} 是 {@code Disallow: /w/}，
 *       而 {@code /wiki/} 正文页允许抓 —— 换句话说，对方欢迎搜索引擎抓正文。
 *       但正文页的 HTML 里塞满了编辑链接、引用列表、信息框表格，
 *       {@link JsoupContentExtractor} 抽出来的"摘要"通常是导航文字；</li>
 *   <li><b>API 直接给出纯文本摘要。</b> {@code prop=extracts&exintro&explaintext}
 *       返回的就是条目开头的散文，<b>不需要任何抽取</b> —— 这是所有来源里最干净的一种，
 *       摘要质量的上限不再是我们的抽取器，而是原文自己；</li>
 *   <li><b>枚举不需要跟链接。</b> {@code list=allpages} 直接给出条目名单，
 *       不需要从 HTML 里猜哪些 {@code <a>} 是正文链接 ——
 *       这正是"跟链接"在通用爬虫里最不可靠的一环。</li>
 * </ol>
 *
 * <h3>两类请求，两种响应，同一个入口</h3>
 * <table border="1">
 *   <caption>本类要能分辨的两种响应</caption>
 *   <tr><th>形态</th><th>识别特征</th><th>产出</th></tr>
 *   <tr><td><b>枚举</b>（{@code list=allpages} 等）</td>
 *       <td>有 {@code query.allpages} 数组</td>
 *       <td>正文为空；{@code links} = 每一条条目对应的"取摘要"请求地址；
 *           {@code sameLevelLinks} = 续页地址（若有）</td></tr>
 *   <tr><td><b>条目</b>（{@code prop=extracts}）</td>
 *       <td>有 {@code query.pages[].extract}</td>
 *       <td>正文 = 摘要；{@code links} 为空（条目不负责发现新页面）</td></tr>
 * </table>
 *
 * <p><b>枚举响应为什么能返回"没有正文"而不是被判成抽取失败：</b>
 * 长度判定已经上移到 {@code CrawlPipeline}（见 {@link ResponseExtractor} 的说明），
 * 而流水线的顺序是"先入队本页链接，再决定正文能否入库"。
 * 所以"只有链接、没有正文"是一种正常形态，不是异常 —— 这正是这条链路能成立的前提。
 *
 * <h3>⚠️ 种子站的 entry_url 必须带上枚举参数</h3>
 * <b>不能只写 {@code https://zh.wikipedia.org/w/api.php}。</b>
 * 不带任何参数时，{@code api.php} 返回的是一份 HTML 格式的接口帮助页（不是 JSON），
 * 于是这一页会被判为「响应无法解析」并置为跳过 ——
 * 现象是「种子加上了、域名也对，但一条都没采到」，且日志里只有一行跳过原因。
 *
 * <p>正确写法是枚举请求的完整地址，见 {@link #enumerationRequestUrl(String)}：
 * <pre>
 * https://zh.wikipedia.org/w/api.php?action=query&amp;format=json&amp;formatversion=2
 *   &amp;list=allpages&amp;apnamespace=0&amp;aplimit=500&amp;maxlag=5
 * </pre>
 * 后续的续页地址与条目地址都由这一页派生（见 {@link #fromEnumeration}），
 * 所以只要入口这一条对了，整条链路就成立。
 *
 * <h3>⚠️ 两个必须知道的限制</h3>
 * <ul>
 *   <li><b>{@code prop=extracts} 需要 TextExtracts 扩展。</b> 维基百科 / 文库 / 词典有，
 *       <b>维基数据没有</b>（它没有条目正文，只有结构化声明）。对维基数据要用
 *       {@code action=wbgetentities}，本类不支持 —— 见 {@code docs/08-roadmap.md}。
 *       在支持的站点上，若某条条目拿不到 extract（重定向残留、被删除），
 *       响应里会有 {@code missing}，本类按"跳过"处理而不是入库一条空内容。</li>
 *   <li><b>枚举是一次性的。</b> 只有种子站入口会被调度器入队（{@code bootstrapSeeds}），
 *       而枚举页不产出正文 → 它最终是"已跳过"终态，不会被再抓一次。
 *       也就是说<b>之后新增的条目不会被自动发现</b>，需要手工把入口 URL 重新入队
 *       或跑一次重建式的补采。这是本期已知的取舍，不是 bug。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaWikiApiExtractor implements ResponseExtractor {

    /**
     * MediaWiki 的 {@code maxlag} 参数。
     *
     * <p>这是这套 API 的礼貌约定：带上它，复制延迟偏高的从库会让 API 直接回 503
     * 并在 {@code Retry-After} 里给出建议等待时间，而不是硬着头皮把一个慢查询压上去。
     * 我们这边的 {@code HttpPageFetcher} 已经把 5xx 当作可重试并尊重 {@code Retry-After}，
     * 因此"对方让我们慢一点"这件事不需要额外代码就能生效 ——
     * 这也是不用 robots 而用 API 之后，仍然算"礼貌采集"的依据。
     */
    private static final int MAX_LAG_SECONDS = 5;

    /** 单次枚举请求取回的条目数。500 是普通用户对 {@code aplimit} 的上限。 */
    private static final int ENUMERATION_LIMIT = 500;

    /**
     * 条目请求对应的原站 HTML 路径。
     *
     * <p>用来拼 {@code canonicalUrl}（给人看的地址），并从 API 地址反推 ——
     * 所有维基媒体项目都是 {@code /w/api.php} 对 {@code /wiki/}，但这个映射并不是
     * MediaWiki 的规范，只是维基媒体集群的事实。反推失败时 canonicalUrl 留空，
     * 不猜。
     */
    private static final String API_PATH = "/w/api.php";

    private static final String HTML_PATH = "/wiki/";

    private final ObjectMapper objectMapper;

    @Override
    public ExtractedContent extract(String body, String url) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            // 拿到的不是 JSON。最常见的原因是抓到的其实是拦截页/HTML 错误页
            // （对方拦了我们、或 3xx 落到登录页），而不是"API 坏了"。
            // 记 DEBUG 而不是 WARN：这种情况在批量采集里会重复出现，WARN 会把日志淹掉。
            log.debug("MediaWiki API 响应不是合法 JSON: url={} err={}", url, e.getMessage());
            return null;
        }

        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            // API 层的错误（参数不对、没有权限、站点不允许匿名读 API…）。
            // 返回 null → 该 URL 被判为"抽取失败"并跳过。这是对的：
            // 这类错误重试一万次也一样，而且它意味着配置有问题，需要人看一眼。
            log.warn("MediaWiki API 返回错误: url={} code={} info={}",
                    url, error.path("code").asText(), error.path("info").asText());
            return null;
        }

        JsonNode query = root.path("query");
        if (query.isMissingNode() || query.isNull()) {
            return null;
        }

        JsonNode allpages = query.path("allpages");
        // 判据里带上"本次请求是不是枚举请求"，而不是只看 allpages 存不存在：
        // 枚举结果为空时（这个 wiki 的命名空间 0 下一条都没有，或已经翻到末页），
        // 服务端会**整个省掉** allpages 这个键，响应体只剩一个空的 query。
        // 只认数组的话这一刻会被判成"结构无法识别"，而这个种子明明是正常跑完的 ——
        // 一个走完全程却说不出为什么停下的采集器，比停下来更麻烦。
        if (allpages.isArray() || isEnumerationRequest(url)) {
            // allpages 缺失时是 MissingNode：它是空的、可以安全迭代，size() 为 0，
            // 因此不需要在这里造一个空数组去补形状。
            return fromEnumeration(allpages, root.path("continue"), url);
        }

        JsonNode pages = query.path("pages");
        if (pages.isArray()) {
            return fromPages(pages, url);
        }

        // 响应是合法 JSON、也没有 error，但结构不是我们认识的两种之一。
        // 常见于把 list 模块和 prop 模块混在一个请求里 —— 那时结果的形状与两者都不同。
        log.debug("MediaWiki API 响应结构无法识别（既不是枚举也不是条目）: url={}", url);
        return null;
    }

    /**
     * 枚举响应 → 「一批取摘要的请求地址」+「一页续页地址」。
     *
     * <p>枚举页本身没有可入库的正文：它返回的标题里没有任何一句是给搜索用的描述。
     * 硬把标题拼成一串文字当正文，会在语料里留下一条"标题列表"式的垃圾文档 ——
     * 它对任何关键词都能命中，却什么也不说明。所以这里返回 {@code text = ""}。
     */
    private ExtractedContent fromEnumeration(JsonNode allpages, JsonNode continueNode, String url) {
        String base = apiBase(url);
        if (base == null) {
            return null;
        }
        List<String> articleUrls = new ArrayList<>(allpages.size());
        for (JsonNode page : allpages) {
            String title = text(page, "title");
            if (title == null || title.isBlank()) {
                continue;
            }
            articleUrls.add(articleRequestUrl(base, title));
        }

        // 续页。MediaWiki 的 continue 是一个对象，可能同时含模块级 token（apcontinue）
        // 与通用的 continue token（后者只在多模块请求时需要）。
        // 这里只认 apcontinue：本类的枚举请求永远是单模块的，通用的那个用不上，
        // 而且把它带上会让下一次请求的形状与本类能解析的两种形状都不匹配。
        List<String> sameLevel = new ArrayList<>(1);
        String nextToken = text(continueNode, "apcontinue");
        if (nextToken != null && !nextToken.isBlank()) {
            String next = withQueryParam(url, "apcontinue", nextToken);
            if (next != null) {
                sameLevel.add(next);
            }
        }

        return new ExtractedContent("枚举页", null, "", null, articleUrls, sameLevel);
    }

    /**
     * 条目响应 → 「标题 + 摘要」。
     *
     * <p>{@code formatversion=2} 下 {@code pages} 是数组（v1 是对象），
     * 且缺失页用 {@code missing: true} 标记。请求里带了 {@code redirects=1}，
     * 因此重定向已经被服务端解掉，这里看到的标题是最终标题 —— 用它做文档标题是对的：
     * 用户搜到的是内容本身，不是那个重定向过来的旧名字。
     */
    private ExtractedContent fromPages(JsonNode pages, String url) {
        for (JsonNode page : pages) {
            if (page.path("missing").asBoolean(false)) {
                // 条目不存在（已被删除、或标题写错）。跳过而不是入库空内容。
                log.debug("MediaWiki 条目不存在，跳过: url={} title={}", url, text(page, "title"));
                return null;
            }
            String extract = text(page, "extract");
            if (extract == null || extract.isBlank()) {
                // 有些条目确实没有引言（极短的消歧义页），或该站点没装 TextExtracts。
                // 一律按"没有正文"处理：CrawlPipeline 会因为正文过短而跳过它，
                // 但它的链接（本类不产出）仍会被跟进。
                log.debug("MediaWiki 条目没有 extract，按无正文处理: url={} title={}",
                        url, text(page, "title"));
                return new ExtractedContent(text(page, "title"), null, "", canonicalOf(url), List.of());
            }
            String title = text(page, "title");
            if (title == null || title.isBlank()) {
                return null;
            }
            // description 留空：extract 本身就是概述，再塞一份进 description
            // 会让 CrawlPipeline 的 buildSummary 优先取 description，
            // 结果是一样的内容走两条路，反而容易出现"摘要比正文还长"这种怪象。
            return new ExtractedContent(title, null, extract, canonicalOf(url), List.of());
        }
        return null;
    }

    /**
     * 本次请求是不是枚举请求。
     *
     * <p>靠 URL 里的 {@code list=allpages} 判断，而不是靠响应体 —— 理由见调用处的注释。
     * 这两个名字（参数名与模块名）是 MediaWiki API 的对外契约，不会随版本变化；
     * 与之相对，响应体的形状在"结果为空"时是会变的。
     */
    private boolean isEnumerationRequest(String url) {
        return url != null && url.contains("list=allpages");
    }

    /**
     * 从本次请求地址反推 API 端点（去掉查询串）。
     *
     * <p>刻意<b>不</b>从种子站的 {@code entry_url} 取：抽取器只拿到响应体与本次 URL，
     * 拿不到种子；而且"枚举请求的地址"本来就等价于端点 —— 少一个需要传进来的参数，
     * 就少一处可能不一致的地方。
     */
    private String apiBase(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getPath() == null) {
                return null;
            }
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            return uri.getScheme() + "://" + uri.getHost() + port + uri.getPath();
        } catch (URISyntaxException e) {
            log.debug("无法从枚举地址反推 API 端点: url={}", url);
            return null;
        }
    }

    /** 从 API 地址反推该条目给人看的地址（{@code /w/api.php} → {@code /wiki/}）。推不出来时返回 null。 */
    private String canonicalOf(String url) {
        String base = apiBase(url);
        if (base == null || !base.endsWith(API_PATH)) {
            return null;
        }
        // ⚠️ 这里**不能**再编码一次。{@link #titleOf} 取回来的是 URL 里的原始形态，
        // 它已经是百分号编码的（构造请求时编过一遍），再编一次会把 % 变成 %25，
        // 得到 https://.../wiki/%25E5%258C%2597... 这种谁都打不开的地址。
        // 这个坑很隐蔽：地址看起来"像编码过的"，只有拿去访问才会发现打不开。
        return base.substring(0, base.length() - API_PATH.length()) + HTML_PATH + titleOf(url);
    }

    /**
     * 从条目请求地址里取回 {@code titles} 参数的值。
     *
     * <p>返回的是<b>URL 里的原始形态</b>（仍是百分号编码的），不是解码后的标题 ——
     * 这正是拼 {@code /wiki/...} 时需要的形态，见 {@link #canonicalOf}。
     */
    private String titleOf(String url) {
        int i = url.indexOf("titles=");
        if (i < 0) {
            return "";
        }
        String rest = url.substring(i + "titles=".length());
        int amp = rest.indexOf('&');
        return amp < 0 ? rest : rest.substring(0, amp);
    }

    /** 构造"取某条摘要"的请求地址。 */
    private String articleRequestUrl(String base, String title) {
        return base + "?action=query"
                + "&format=json"
                + "&formatversion=2"
                + "&prop=extracts"
                // exintro：只要引言。整篇条目动辄几百 KB，而我们入库的摘要只有 300 字，
                // 把全文拉回来再丢掉大部分，是对对方带宽和我们自己内存的双重浪费。
                + "&exintro=1"
                // explaintext：直接给纯文本，不要带 HTML 标签的版本。
                // 这是"不需要抽取"的由来 —— 拿到就能用。
                + "&explaintext=1"
                // redirects：让服务端把重定向解掉，否则重定向页拿不到 extract，
                // 我们会把一批本该有内容的条目当成"不存在"。
                + "&redirects=1"
                + "&maxlag=" + MAX_LAG_SECONDS
                + "&titles=" + enc(title);
    }

    /** 枚举请求地址（供文档与种子站配置引用，避免这个串散落在几处）。 */
    public static String enumerationRequestUrl(String base) {
        return base + "?action=query"
                + "&format=json"
                + "&formatversion=2"
                + "&list=allpages"
                // 命名空间 0 就是条目正文。带上讨论页、模板页、分类页只会污染语料。
                + "&apnamespace=0"
                + "&aplimit=" + ENUMERATION_LIMIT
                + "&maxlag=" + MAX_LAG_SECONDS;
    }

    /** 替换或追加一个查询参数，其余参数原样保留。URI 不合法时返回 null。 */
    private String withQueryParam(String url, String key, String value) {
        String head;
        String query;
        int q = url.indexOf('?');
        if (q < 0) {
            head = url;
            query = "";
        } else {
            head = url.substring(0, q);
            query = url.substring(q + 1);
        }
        StringBuilder sb = new StringBuilder(query.length() + 64);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (k.equals(key)) {
                // 旧的续页 token 必须丢掉：留着就会出现两个同名参数，
                // 而服务端取哪一个是没有保证的。
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(pair);
        }
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(key).append('=').append(enc(value));
        String result = head + "?" + sb;
        try {
            new URI(result);
            return result;
        } catch (URISyntaxException e) {
            log.debug("续页地址不合法，已丢弃: {}", result);
            return null;
        }
    }

    /**
     * 查询参数值的编码。
     *
     * <p>用 {@code %20} 而不是 {@code +} 表示空格：{@code +} 表示空格只在
     * {@code application/x-www-form-urlencoded} 下成立，MediaWiki 两种都吃，
     * 但 {@code +} 会在其它环节被当成字面加号（比如有人手工复制这个地址去浏览器里试），
     * 而 {@code %20} 在任何地方都只有一个意思。
     *
     * <p>这一步不能省：条目标题里出现 {@code &} {@code =} {@code |} 是常态
     * （消歧义页标题带括号、含 {@code &} 的机构名），
     * 不做编码拼出来的地址会被我们自己的 {@code UrlNormalizer} 判为非法 URI 而直接丢弃 ——
     * 表现是"某些条目永远采不到"，且没有任何日志。
     */
    private static String enc(String raw) {
        if (raw == null) {
            return "";
        }
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
