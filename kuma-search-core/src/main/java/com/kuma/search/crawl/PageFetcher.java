package com.kuma.search.crawl;

/**
 * 页面抓取：把 URL 变成 {@link PageFetchResult}。
 *
 * <p>定成接口是为了留出「渲染 JS 页面」这条路：无头浏览器（Playwright / Puppeteer）
 * 与本实现的差别是「拿到 HTML 之前多了一步」，而调用方只关心「有没有 HTML」，
 * 完全不需要知道自己面对的是哪种抓取方式。届时的替换点只有这一个。
 *
 * <p>实现方必须自己保证这几件事（它们不属于调用方的职责）：
 * <ul>
 *   <li><b>超时</b> —— 连接与读取都要设，且都要设得有限。抓的是别人的服务器，
 *       一个不响应的连接会占住调度线程直到天荒地老。</li>
 *   <li><b>响应体大小上限</b> —— 必须边读边判。先读进内存再判断大小的写法，
 *       在遇到一个 4GB 的响应时进程已经没了。</li>
 *   <li><b>不跟随跨协议重定向</b> —— 特别是跳到 {@code file://} 或 {@code ftp://}。</li>
 *   <li><b>内网地址拦截</b>（SSRF）—— 见 {@link HttpPageFetcher}。</li>
 * </ul>
 *
 * <p>实现<b>不做</b>限速。按域名限速需要知道域名维度的全局状态（一个域当前有几个请求在飞），
 * 那是调度层的知识，放在这里会让每个实现都不得不重新造一遍。限速在
 * {@code CrawlPipeline} / {@code CrawlScheduler}，见 {@code CrawlRateLimiter}。
 */
public interface PageFetcher {

    /**
     * 抓取一个页面。
     *
     * @param url          归一化后的绝对 URL
     * @param etag         上次抓到的 ETag；非空时会带 {@code If-None-Match}，
     *                     命中则返回 {@link PageFetchResult#isNotModified()} 为真的结果
     * @param lastModified 上次抓到的 Last-Modified；非空且没有 etag 时带 {@code If-Modified-Since}
     * @return 抓取结果，永不返回 null，也永不抛异常（失败一律编码进结果）
     */
    PageFetchResult fetch(String url, String etag, String lastModified);
}
