package com.kuma.search.crawl;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 基于 jsoup 的正文抽取实现（{@code kuma.crawl.extractor=jsoup}，默认）。
 *
 * <h3>它比 {@link RegexContentExtractor} 强在哪 —— 以及为什么不违反那个类的结论</h3>
 * {@code RegexContentExtractor} 的类注释里写过「不引入 jsoup」，理由是
 * 「jsoup 不可替代的能力是按站点写 CSS 选择器，而那个能力本期用不上」。
 * 这个判断本身没错，但它只看了 jsoup 的<b>选择器</b>能力。真正让它值得引入的是另外两件：
 *
 * <ol>
 *   <li><b>把 HTML 解析成一棵树，而不是用正则去猜。</b>正则版必然在这几处出错：
 *       {@code <script>} 内容里出现 {@code "</script>"} 字面量、注释里嵌注释、
 *       未闭合的标签（真实网页里非常常见）、表格的结构。
 *       这些都是「解析」问题，正则只能靠近似；</li>
 *   <li><b>模板噪声的剔除。</b>这是本类最主要的实际收益 ——
 *       导航栏、侧边栏、页脚、评论区、分享按钮的文字会被正则版<b>原样当成正文</b>，
 *       于是每一页都带着一整份导航文字进敏感词过滤、进指纹、进摘要。
 *       这不是"稍微不准"，是让摘要（{@code content} 字段存的正是摘要）变成
 *       一段站点导航。剔除模板需要知道**元素的层级与类名**，这必须有一棵树。</li>
 * </ol>
 *
 * <p>换句话说：本类不依赖"针对每个站点写规则"，它靠的是 HTML5 的语义标签
 * （{@code <nav>} {@code <footer>} {@code <aside>}）加上一份通用的类名黑名单。
 * 这与「按站点写选择器」是两回事，后者仍然没做，仍然留给将来的
 * 「无头浏览器 + 站点规则」实现。{@code RegexContentExtractor} 保留为
 * 零依赖的对照组（{@code kuma.crawl.extractor=regex}），
 * 两者可随时切换、便于比对抽取质量 —— 这是把它做成接口的直接好处。
 *
 * <h3>⚠️ 模板剔除是"宁可少剔"，不是"宁可错剔"</h3>
 * 剔多了的后果是<b>半可见的</b>：正文变短 → 低于入库阈值 → 该页只入库不了正文，
 * 但它的出站链接仍会被跟进（长度判定不在本类做，见 {@code ContentExtractor} 的类注释），
 * 所以剔过头的表现是"这个栏目的页面一条都没进来"，而不是"子树整片消失"。
 * 日志里只有一行 DEBUG。因此这里有两条纪律：
 * <ul>
 *   <li>类名黑名单用<b>整词匹配</b>而不是子串包含。<code>.*ad.*</code> 这种写法会把
 *       {@code load}、{@code header}、{@code download}、{@code shadow} 全部命中 ——
 *       这不是理论问题，是这类过滤器最常见的翻车方式。
 *       所以先把 {@code class}/{@code id} 按 {@code - _ 空白} 切成词，再逐词比对；</li>
 *   <li>黑名单只收「几乎不可能是内容容器」的词。{@code content}、{@code page}、
 *       {@code main}、{@code article} 这类词绝不进黑名单。</li>
 * </ul>
 * 剔除清单集中写在 {@link #BOILERPLATE_SELECTOR} 与 {@link #BOILERPLATE_CLASS_TOKENS}，
 * 调整时改这两处即可。
 *
 * <h3>链接必须在剔除之前收集，正文在剔除之后提取</h3>
 * 这个顺序是刻意的、也是容易被写反的：站点的导航栏与栏目列表恰恰是爬虫
 * <b>发现新页面</b>的主要入口，剔除它们是为了让正文干净，不是为了不发现链接。
 * 反过来，正文必须用剔除后的树，否则每一页的正文里都混着导航文字。
 *
 * <h3>输入边界</h3>
 * 本类拿到的是<b>已经解码、已有体积上限</b>的 HTML 字符串：编码判定与
 * {@code kuma.crawl.max-content-bytes} 的截断都在 {@code HttpPageFetcher} 里完成
 * （jsoup 只有在拿到字节流时才能做编码自动识别，而这里签名的入参是 String，
 * 所以那件事本来就只能在取回侧做）。
 *
 * <h3>与正则版的装配是互斥的</h3>
 * {@code kuma.crawl.extractor} 取 {@code jsoup}（默认）时本类生效、
 * {@code RegexContentExtractor} 不装配，反之亦然。两者都是被扫描的
 * {@code @Component}，因此<b>不能</b>用 {@code @ConditionalOnMissingBean} 来二选一
 * —— 那个注解只在自动配置类里可靠生效，放在组件扫描到的类上，生效顺序是不确定的
 * （{@code RegexContentExtractor} 的类注释里已经记过这条）。互斥的条件属性是这里唯一可靠的做法。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kuma.crawl", name = "extractor",
        havingValue = "jsoup", matchIfMissing = true)
public class JsoupContentExtractor implements ContentExtractor {

    /**
     * 结构上就可判定为非正文的标签，连同其内容一起删除。
     *
     * <p>{@code <header>} 刻意<b>不在</b>这里：站点的正文标题与作者署名常常就在
     * {@code <article><header>} 里，删掉它会让标题抽取退化成只能靠 {@code <title>}。
     * 首页那种纯粹的站点头部则由类名黑名单里的 {@code header} 词条处理。
     */
    private static final String BOILERPLATE_SELECTOR = String.join(", ",
            "script", "style", "noscript", "iframe", "svg", "canvas", "template",
            "form", "button", "select", "textarea", "dialog",
            "nav", "footer", "aside");

    /**
     * 类名/ID 的<b>整词</b>黑名单。
     *
     * <p>词是按 {@code - _ 空白} 切分后逐个比对的，所以 {@code site-footer} 会被命中
     * （含 {@code footer} 一词），而 {@code load-more}、{@code download} 不会
     * （切出来是 {@code load}/{@code more}、{@code download}）。
     *
     * <p>标注了 {@code ⚠} 的几个是<b>故意留在这里、但确实可能误伤</b>的，
     * 需要时优先从这里删：{@code header}（可能包住页面标题）、
     * {@code comment}（少数站点把正文放在 {@code comment-content} 里）。
     * {@code hot} / {@code links} 这类语义偏弱的词同样属于"可删候选"，
     * 它们留在这里是因为对门户型站点收益明显。
     */
    private static final Set<String> BOILERPLATE_CLASS_TOKENS = Set.of(
            // 导航与骨架
            "nav", "navbar", "navigation", "menu", "breadcrumb", "breadcrumbs",
            "sidebar", "footer", "foot",
            "header",           // ⚠ 见上
            "toolbar", "pager", "pagination",
            // 互动与推广
            "comment", "comments", "commentlist",  // ⚠ 见上
            "share", "sharing", "social", "subscribe", "subscription",
            "related", "recommend", "recommended", "hot", "ranking",
            "ad", "ads", "advert", "advertisement", "adsense", "banner",
            "promo", "promotion", "sponsor", "sponsored",
            // 站点信息与浮层
            "copyright", "license", "disclaimer", "friendlink", "friendlinks", "links",
            "popup", "modal", "mask", "overlay", "cookie", "cookies", "gdpr",
            "login", "signin", "signup", "register");

    /** 抽取的出站链接数量上限。与 {@link RegexContentExtractor} 取同一个值。 */
    private static final int MAX_LINKS = 500;

    /** 需要一并压成普通空格的"伪装空白"：不换行空格（U+00A0）与全角空格（U+3000）。 */
    private static final Pattern PSEUDO_SPACE = Pattern.compile("[\\u00a0\\u3000]");

    /** 标题兜底时读取的 meta。{@code og:title} 在社交分享型页面里比 {@code <title>} 更干净。 */
    private static final String[] TITLE_META =
            {"meta[property=og:title]", "meta[name=twitter:title]"};

    /** 描述兜底，理由同上。 */
    private static final String[] DESCRIPTION_META = {
            "meta[name=description]", "meta[property=og:description]",
            "meta[name=twitter:description]"};

    @Override
    public ExtractedContent extract(String html, String url) {
        if (html == null || html.isBlank()) {
            return null;
        }

        // 传 baseUri 是为了让 absUrl 能解析相对路径（见下面的链接收集）：
        // 返回绝对 URL 能让 UrlNormalizer 少依赖"base 一定正确"这个前提。
        Document doc = Jsoup.parse(html, url == null ? "" : url);

        String title = firstText(doc.title(), doc, "h1", TITLE_META);
        if (title == null || title.isEmpty()) {
            // 与正则版同一条策略：没有标题的页面无法在结果里展示。
            // 硬凑一个标题（取正文首句）会让用户以为那是站点写的。
            log.debug("页面无可用标题，判为抽取失败: {}", url);
            return null;
        }

        // ---- 以下四项都必须在"剔除模板"之前取 ----
        // 顺序不能反：canonical/描述/链接都属于页面的元信息，剔除后可能连带消失。
        String description = firstAttr(doc, DESCRIPTION_META, "content");
        String canonical = doc.selectFirst("link[rel=canonical]") == null
                ? null : blankToNull(doc.selectFirst("link[rel=canonical]").absUrl("href"));
        List<String> links = collectLinks(doc);

        String text = extractText(doc);

        // 这里刻意【不】做长度判定。正文过短的页面照样返回，因为它的 links 仍然有价值 ——
        // 列表页/索引页/分页页的正文都很短，而丢掉它们等于丢掉整棵子树。
        // 长度阈值由 CrawlPipeline 应用（见 ContentExtractor 的类注释）。
        return new ExtractedContent(title, description, text, canonical, links);
    }

    /**
     * 剔除模板噪声后取出正文纯文本。
     *
     * <p>只处理 {@code <body>}：{@code <head>} 里的 {@code <title>}、
     * {@code <meta>} 若参与 {@code text()} 会变成正文的第一句话，
     * 而它们的内容已经单独抽过了。
     */
    public String extractText(Document doc) {
        Element body = doc.body();
        if (body == null) {
            // 解析不出 body 说明这根本不是一份 HTML（可能是 JSON 错误页、纯文本）。
            // 返回空串让上层的长度判定把它跳过，而不是抛异常中断整批采集。
            return "";
        }
        removeBoilerplate(body);
        return body.text();
    }

    /**
     * 删除模板噪声元素。
     *
     * <p>两轮：先按标签（快，且不需要读 class），再按类名/ID 的整词匹配。
     * 分两轮而不是合成一个大选择器，是因为类名那一轮需要 Java 侧做词法判断，
     * 写不进 CSS 选择器（{@code ~=} 是子串匹配，正是上面说要避免的那种）。
     */
    private void removeBoilerplate(Element root) {
        Elements structural = root.select(BOILERPLATE_SELECTOR);

        List<Element> byClass = new ArrayList<>();
        for (Element el : root.getAllElements()) {
            if (isBoilerplateClass(el)) {
                byClass.add(el);
            }
        }

        // 先删结构性的，再删类名命中项。删除操作本身会顺带移除子树，
        // 因此第二轮里可能有一批元素已经不在文档上了 —— 对游离节点调 remove() 是安全的空操作。
        structural.remove();
        byClass.forEach(Element::remove);
    }

    /** 元素的 {@code class} 与 {@code id} 中是否有任何一个整词命中黑名单。 */
    private boolean isBoilerplateClass(Element el) {
        for (String token : splitTokens(el.className())) {
            if (BOILERPLATE_CLASS_TOKENS.contains(token)) {
                return true;
            }
        }
        for (String token : splitTokens(el.id())) {
            if (BOILERPLATE_CLASS_TOKENS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把 {@code class}/{@code id} 切成整词。
     *
     * <p>三个分隔符都要算：{@code class="a b"} 是空格分隔的多类名，
     * {@code id="site-footer"} 是连字符，{@code class="side_bar"} 是下划线。
     * 统一转小写是因为 HTML 的类名大小写不敏感而作者写法五花八门。
     */
    private List<String> splitTokens(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[\\s\\-_]+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * 收集出站链接，返回<b>绝对</b> URL。
     *
     * <p>与正则版不同，这里不再返回原始 href：jsoup 已经知道 baseUri，
     * 解析相对路径是它的本职工作，而且在错误的 base 上解析是正则版最容易出的问题。
     * 返回绝对 URL 对 {@code CrawlPipeline} 是安全的 ——
     * {@code UrlNormalizer.normalize(raw, base)} 对已经是绝对的入参照样能处理。
     *
     * <p>过滤掉的目标（{@code mailto:}/{@code javascript:}/纯锚点）与正则版一致，
     * 但这里多一层：{@code absUrl} 解析失败时返回空串，说明这个 href 连 URI 都算不上。
     */
    public List<String> collectLinks(Document doc) {
        List<String> links = new ArrayList<>();
        for (Element a : doc.select("a[href]")) {
            if (links.size() >= MAX_LINKS) {
                // 截断要有痕迹：一份 5000 链接的列表页被静默截到 500
                // 会让人以为"这个站点只有 500 个页面"。
                log.debug("出站链接达上限 {}，其余已截断: href={}", MAX_LINKS, a.attr("href"));
                break;
            }
            String href = blankToNull(a.absUrl("href"));
            if (href == null || href.startsWith("#")) {
                continue;
            }
            links.add(href);
        }
        return links;
    }

    /** 依次尝试 title、若干选择器，取第一个非空文本并做空白压缩。 */
    private String firstText(String title, Document doc, String tag, String[] metas) {
        String candidate = clean(title);
        if (candidate != null) {
            return candidate;
        }
        candidate = clean(textOf(doc.selectFirst(tag)));
        if (candidate != null) {
            return candidate;
        }
        return firstAttr(doc, metas, "content");
    }

    /** 依次尝试若干 meta 选择器，取第一个非空属性值。 */
    private String firstAttr(Document doc, String[] selectors, String attr) {
        for (String selector : selectors) {
            Element el = doc.selectFirst(selector);
            if (el != null) {
                String value = clean(el.attr(attr));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private String textOf(Element el) {
        return el == null ? null : el.text();
    }

    /**
     * 空白压缩。
     *
     * <p>jsoup 的 {@code text()} 已经把连续空白压成一个空格了，但 meta 的
     * {@code content} 是原样属性值（里面可能有换行与制表符），
     * 落到 {@code t_search_content.title} 里会变成一行带换行的标题。
     *
     * <p>U+00A0（不换行空格）与 U+3000（全角空格）必须显式处理：
     * 它们不被 {@code \\s} 匹配，留在标题里会让前端的「点这个词再搜一次」
     * 搜不到东西 —— 用户看到的字面与传出去的关键词差一个看不见的字符。
     */
    private String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = PSEUDO_SPACE.matcher(s).replaceAll(" ").trim().replaceAll("\\s+", " ");
        return t.isEmpty() ? null : t;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
