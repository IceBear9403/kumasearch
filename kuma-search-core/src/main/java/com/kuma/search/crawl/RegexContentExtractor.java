package com.kuma.search.crawl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于正则与字符串扫描的正文抽取实现。
 *
 * <p><b>它现在的定位：默认实现之外的对照实现。</b>当初写这个类时项目里没有 jsoup，
 * 理由是「jsoup 不可替代的是按站点写 CSS 选择器，而那个能力本期用不上」。
 * 后来 {@link JsoupContentExtractor} 被引入并成为默认 —— 引入它的理由不是选择器，
 * 而是另外两点（正确的解析、模板噪声剔除），详见那个类的注释。
 *
 * <p>本类因此<b>保留</b>下来，由 {@code kuma.crawl.extractor=regex} 启用：
 * 一是作为零依赖实现，依赖出问题时还有一条能跑的路径；
 * 二是抽取质量出问题时，有一个"另一套算法"可以对照 ——
 * 当某批页面在 jsoup 版下被跳过，切成本类跑一遍就能分清
 * 「是模板剔除剔过头了」还是「这批页面本来就没正文」。
 *
 * <p>它<b>不是</b>一个 HTML 解析器，也不打算是：正则匹配嵌套标签在原理上就不可能正确。
 * 但这里的用途不需要正确的树结构 —— 需要的是「去掉标签、留下文字、别把
 * {@code <script>} 里的 JS 混进来」，这几件事正则做得又准又快。
 * 它<b>不做模板剔除</b>：导航栏、页脚的文字会被原样当成正文，这是它与 jsoup 版最主要的差距。
 *
 * <p>已知会失效的情形（如实记录，避免过度信任）：
 * <ul>
 *   <li>正文由 JS 渲染 —— 拿到的是空骨架，会表现为「抽取结果过短」被跳过；</li>
 *   <li>{@code <script>} 里含 {@code "</script>"} 字面量 —— 会提前结束，但这属于代码生成失误，极罕见；</li>
 *   <li>表格类页面 —— 单元格之间会被压成一个空格，行结构丢失。表格内容不适合被当正文检索，
 *       这里不做处理是有意的取舍。</li>
 * </ul>
 *
 * <p><b>两个实现的装配方式</b>：本类与 {@link JsoupContentExtractor} 都是被扫描的
 * {@code @Component}，用<b>互斥的</b> {@code @ConditionalOnProperty}（{@code kuma.crawl.extractor}）
 * 保证任何时刻只有一个 {@link ContentExtractor} Bean 存在 —— {@code CrawlPipeline}
 * 是构造器注入单个 Bean，多一个会直接启动失败。
 *
 * <p>这里刻意<b>不</b>用 {@code @Primary}，也<b>不</b>用 {@code @ConditionalOnMissingBean}：
 * 后者只在自动配置类里可靠生效，放在被扫描的 {@code @Component} 上生效顺序不确定
 * （两个实现谁先被注册，结果就不同）。互斥条件没有这个顺序问题。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kuma.crawl", name = "extractor", havingValue = "regex")
public class RegexContentExtractor implements ContentExtractor {

    /** {@code <title>}。跨行是常态，必须用 DOTALL。 */
    private static final Pattern TITLE = Pattern.compile(
            "<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** {@code <meta name="description" content="...">}。属性顺序两种都要认。 */
    private static final Pattern META_DESC_NAME_FIRST = Pattern.compile(
            "<meta[^>]+name\\s*=\\s*[\"']description[\"'][^>]*content\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern META_DESC_CONTENT_FIRST = Pattern.compile(
            "<meta[^>]+content\\s*=\\s*[\"']([^\"']*)[\"'][^>]*name\\s*=\\s*[\"']description[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** {@code <link rel="canonical" href="...">}，同样认两种属性顺序。 */
    private static final Pattern CANONICAL_REL_FIRST = Pattern.compile(
            "<link[^>]+rel\\s*=\\s*[\"']canonical[\"'][^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern CANONICAL_HREF_FIRST = Pattern.compile(
            "<link[^>]+href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*rel\\s*=\\s*[\"']canonical[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 整块丢弃的标签（含内容）。{@code <script>} 不丢的话 JS 会被当成正文送进敏感词过滤。 */
    private static final Pattern DROPPED_BLOCKS = Pattern.compile(
            "<(script|style|noscript|iframe|svg|canvas|template)\\b[^>]*>.*?</\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** HTML 注释。里面常藏着被注释掉的旧正文，留着会造成「采到了看不到的内容」。 */
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** 所有剩余标签。 */
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);

    /** {@code <a href="...">}，用于收集出站链接。 */
    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 连续空白（含 {@code &nbsp;}），压缩成一个空格。 */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[\\s\\u00a0\\u3000]+");

    /** 抽取的出站链接数量上限。防止超长列表页产生几十万个链接把内存吃光。 */
    private static final int MAX_LINKS = 500;

    @Override
    public ExtractedContent extract(String html, String url) {
        if (html == null || html.isBlank()) {
            return null;
        }

        String title = clean(firstGroup(TITLE, html));
        if (title == null || title.isEmpty()) {
            // 没有标题的页面无法在搜索结果里展示。与其用正文首句硬凑一个标题，
            // 不如判为抽取失败 —— 硬凑出来的标题会让人以为是站点写的，实际是我们猜的。
            log.debug("页面无 title，判为抽取失败: {}", url);
            return null;
        }

        String description = clean(firstNonNull(
                firstGroup(META_DESC_NAME_FIRST, html),
                firstGroup(META_DESC_CONTENT_FIRST, html)));

        String canonical = firstNonNull(
                firstGroup(CANONICAL_REL_FIRST, html),
                firstGroup(CANONICAL_HREF_FIRST, html));

        List<String> links = extractLinks(html);

        String text = toPlainText(html);

        // 与 JsoupContentExtractor 一致：这里不判长度，过短的页面照样返回，
        // 因为它的 links 还有价值。阈值由 CrawlPipeline 应用 —— 见 ContentExtractor 的类注释。
        return new ExtractedContent(title, description, text, canonical, links);
    }

    /**
     * HTML → 纯文本。
     *
     * <p>顺序不能颠倒：先丢 {@code <script>}/{@code <style>} 整块，再丢注释，最后才剥标签。
     * 若先剥标签，{@code <script>} 里的 JS 就已经混进文本了，此时它和正文再无区别，
     * 无法分离 —— 这是这类抽取器最容易写错的一步。
     */
    public String toPlainText(String html) {
        String s = DROPPED_BLOCKS.matcher(html).replaceAll(" ");
        s = HTML_COMMENT.matcher(s).replaceAll(" ");
        s = ANY_TAG.matcher(s).replaceAll(" ");
        s = decodeEntities(s);
        s = WHITESPACE_RUN.matcher(s).replaceAll(" ");
        return s.trim();
    }

    /**
     * 收集出站链接的原始 href。
     *
     * <p>不在这里做归一化与去重 —— 那些是 {@link UrlNormalizer} 的职责，
     * 而且需要 base URL 与种子站范围才能判定。这里只负责「把候选捞出来」。
     */
    public List<String> extractLinks(String html) {
        List<String> links = new ArrayList<>();
        Matcher m = ANCHOR.matcher(html);
        while (m.find() && links.size() < MAX_LINKS) {
            String href = m.group(1) == null ? null : m.group(1).trim();
            if (href != null && !href.isEmpty() && !href.startsWith("#")) {
                // 纯锚点链接是页内跳转，不属于「出站链接」，且不归一化的话都变成同一页
                links.add(href);
            }
        }
        return links;
    }

    /**
     * 解码常见 HTML 实体。
     *
     * <p>只处理命名实体表里最常用的那些 + 数字实体。全表有 2000+ 个，而真正出现在
     * 正文里的就是 {@code &nbsp; &amp; &lt; &gt; &quot; &#39;} 这几个 ——
     * 一个 2000 项的 Map 常驻内存换来的收益可以忽略。
     *
     * <p>数字实体必须处理：中文站点的标点常被写成 {@code &#8220;} 这类形式，
     * 不解码的话敏感词匹配会漏（词库里的引号与页面上的编码形式对不上），
     * 而且正文里会残留一串看不懂的数字。
     */
    public String decodeEntities(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            // 实体最长 32 字符（&#x1F600; 这类），超过就一定是普通文本里的 &
            if (semi < 0 || semi - i > 32) {
                sb.append(c);
                i++;
                continue;
            }
            String entity = s.substring(i + 1, semi);
            String decoded = decodeEntity(entity);
            if (decoded == null) {
                // 不是已知实体就原样保留。这里不猜 —— 猜错会破坏正文（例如某段代码片段里
                // 的 & 与后面的分号恰好构成一个像实体的串）。
                sb.append(c);
                i++;
            } else {
                sb.append(decoded);
                i = semi + 1;
            }
        }
        return sb.toString();
    }

    /** 解码单个实体名（不含 {@code &} 与 {@code ;}）。不认识时返回 null。 */
    private String decodeEntity(String entity) {
        if (entity.isEmpty()) {
            return null;
        }
        if (entity.charAt(0) == '#') {
            int codePoint;
            try {
                codePoint = entity.length() > 1 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')
                        ? Integer.parseInt(entity.substring(2), 16)
                        : Integer.parseInt(entity.substring(1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (codePoint <= 0 || codePoint > Character.MAX_CODE_POINT) {
                return null;
            }
            return new String(Character.toChars(codePoint));
        }
        return switch (entity) {
            case "nbsp" -> " ";
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "middot" -> "·";
            case "hellip" -> "…";
            case "mdash" -> "—";
            case "ndash" -> "–";
            case "ldquo" -> "“";
            case "rdquo" -> "”";
            case "lsquo" -> "‘";
            case "rsquo" -> "’";
            default -> null;
        };
    }

    /** 取第一个捕获组。 */
    private String firstGroup(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : null;
    }

    private String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /** 去标签、解码实体、压缩空白。用于 title / description 这类单行字段。 */
    private String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = ANY_TAG.matcher(s).replaceAll(" ");
        t = decodeEntities(t);
        t = WHITESPACE_RUN.matcher(t).replaceAll(" ").trim();
        return t;
    }
}
