package com.kuma.search.crawl;

/**
 * 正文抽取（HTML）：把一坨 HTML 变成「标题 + 描述 + 正文文本 + 出站链接」。
 *
 * <p>数据形状与方法签名都在 {@link ResponseExtractor} —— 本接口只加了一层含义：
 * <b>「这是处理 HTML 的实现，并且同一时刻只有一个」</b>。
 * 当前有两个实现（{@link JsoupContentExtractor} 默认、{@link RegexContentExtractor} 对照），
 * 由 {@code kuma.crawl.extractor} 二选一装配，见 {@link RegexContentExtractor} 的类注释。
 *
 * <p>定成接口而不是直接写实现，是因为这里<b>一定会被替换</b>：当前是 HTML 解析，
 * 对结构规范的页面够用，但目前大量站点的正文由 JS 渲染（解析出来的是一个空壳）。
 * 届时的替换路径是「换一个用无头浏览器渲染后抽取的实现」，调用方（{@code CrawlPipeline}）
 * 一行都不用改 —— 与 {@link PageFetcher} 是同一个思路。
 *
 * <p><b>抽取失败不是异常，是返回 null。</b>采集面向的是不可控的第三方页面，
 * 结构识别不出来是常态（登录墙、验证码页、改版后的新模板）。
 * 把它做成受检异常会逼着每一层调用点写 try-catch，最终必然被吞掉；
 * 做成运行时异常则会让一个坏页面中断整批采集。返回 null 让调用方明确地
 * 「记录原因 → 标记跳过/重试」。
 *
 * <h3>⚠️ 实现方不做长度判定，只做「解得出 / 解不出」</h3>
 * 正文长度是否够格入库由调用方用 {@link ExtractedContent#isTooShort(int)} 判定。
 * 这条分工不是形式主义，它修掉了一个真实的漏采：
 * <ul>
 *   <li>以前抽取器自己判长度、过短就返回 null，于是<b>过短页面的出站链接一起丢了</b> ——
 *       而「正文短但链接多」正是列表页/索引页/分页页的典型形态，
 *       丢掉它们的链接等于整棵子树都不再被发现；</li>
 *   <li>对 {@link MediaWikiApiExtractor} 的枚举请求更是致命的：那种响应本来就是
 *       「没有正文，只有一批链接」，若长度判定发生在抽取器里，枚举这一步直接不成立。</li>
 * </ul>
 * 现在调用方的顺序是「先入队本页链接，再决定正文能不能入库」，见 {@code CrawlPipeline#doProcess}。
 */
public interface ContentExtractor extends ResponseExtractor {
}
