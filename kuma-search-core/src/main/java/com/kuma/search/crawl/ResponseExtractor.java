package com.kuma.search.crawl;

import java.util.List;

/**
 * 把一坨原始响应解成「标题 + 描述 + 正文文本 + 出站链接」。
 *
 * <p>之所以把这一层单独抽出来，而不是让所有实现都挂到 {@link ContentExtractor} 下面，
 * 是因为<b>装配方式不同</b>：{@link JsoupContentExtractor} 与 {@link RegexContentExtractor}
 * 是<b>二选一</b>的（{@code kuma.crawl.extractor} 决定装配哪个，任何时刻只有一个
 * {@link ContentExtractor} Bean），而 {@link MediaWikiApiExtractor} 是<b>始终存在</b>的
 * 第二个实现 —— 它服务的是另一种种子站，不是另一种抽取策略。
 *
 * <p>如果三者共用一个接口，{@code CrawlPipeline} 按类型注入就会变成"两个候选 Bean"
 * 而在启动时报冲突。用一个上层接口把它们分开，正好让"二选一"与"按类型选用"
 * 两种语义各自落在类型系统里，而不是靠约定或 {@code @Primary} 记住。
 *
 * <p>调用方按种子站的 {@code source_type} 选用实现，见 {@code CrawlPipeline}。
 */
public interface ResponseExtractor {

    /**
     * 抽取结果。
     *
     * @param title        页面标题。缺失时返回 null，由调用方决定是否放弃该页
     * @param description  概述性质的元信息（HTML 的 meta description、API 的摘要字段）；
     *                     缺失时为 null
     * @param text         去标签后的正文纯文本。<b>这是送去做敏感词过滤与指纹计算的东西</b>，
     *                     不直接入库 —— 入库的是摘要，见 {@code CrawlPipeline}。
     *                     允许为空（枚举型响应就是空的），此时 {@link #isTooShort(int)} 为真
     * @param canonicalUrl 页面声明的规范链接（{@code <link rel="canonical">} 或 API 对应的原站地址），
     *                     可能为 null
     * @param links        原始出站链接（未归一化、未解析相对路径），由调用方处理。
     *                     它们是<b>下一层</b>的内容，入队时深度 +1
     * @param sameLevelLinks 与本页<b>同一层</b>的链接：续页、翻页、"加载更多"。
     *                     它们指向的是"同一个列表的下一屏"，不是更深一层的内容，
     *                     因此入队时深度<b>不变</b>。见下方说明
     */
    record ExtractedContent(
            String title,
            String description,
            String text,
            String canonicalUrl,
            List<String> links,
            List<String> sameLevelLinks) {

        /**
         * 兼容"没有同层链接"的调用点（HTML 抽取器目前都属于这一类）。
         *
         * <p>它同时也是 {@code links} 的防御性默认：<b>null 会被归一成空列表</b>，
         * 因为下游是 {@code for (String raw : extracted.links())} 这种写法，
         * 一个 null 会直接抛 NPE 并让整批采集中断。
         */
        public ExtractedContent(String title, String description, String text,
                                String canonicalUrl, List<String> links) {
            this(title, description, text, canonicalUrl, links, List.of());
        }

        /**
         * ⚠️ 同层链接为什么必须与普通链接分开
         *
         * <p>因为 {@code t_crawl_seed.max_depth} 是"距入口的最大链接层级"。
         * 如果把续页当普通链接（深度 +1）处理，一个 500 条的列表翻到第 5 屏就"用掉"了 5 层深度，
         * 于是这个字段实际上变成了"最多翻几屏" —— 而配种子站的人读到的语义是"层级"。
         * 后果是一句话都看不出来的少采：配 {@code max_depth=3} 的维基种子只采到几百条就安静停下，
         * 没有任何报错，与"这个站本来就没内容"完全无法区分 ——
         * 这正是本项目反复想避免的那类故障（见 {@code IndexBootstrapJob} 的类注释）。
         */
        public ExtractedContent {
            links = links == null ? List.of() : links;
            sameLevelLinks = sameLevelLinks == null ? List.of() : sameLevelLinks;
        }

        /**
         * 正文是否短到不足以构成一个可检索的页面。
         *
         * <p>由 {@code CrawlPipeline} 用来判定「跳过」—— 注意是跳过<b>入库</b>，
         * 不是跳过这个页面本身：它的 {@link #links} 仍然会被入队。
         */
        public boolean isTooShort(int minLength) {
            return text == null || text.length() < minLength;
        }
    }

    /**
     * 抽取正文。
     *
     * <p><b>不要在实现里按长度提前返回 null。</b>只要响应解得开、拿得到标题，
     * 就把它返回出来，哪怕 {@code text} 是空的 —— 链接的价值与正文长度无关。
     *
     * @param body 原始响应体（HTML 或 API 的 JSON，取决于种子站的 {@code source_type}）
     * @param url  页面 URL，既用于日志，也是 API 实现推导同站其他请求地址的依据
     * @return 抽取结果；响应为空、解不开、或拿不到标题时返回 null
     */
    ExtractedContent extract(String body, String url);
}
