package com.kuma.search.crawl;

import lombok.Getter;

/**
 * 一次抓取的结果。
 *
 * <p><b>这个类刻意没有用异常来表达失败。</b>采集的失败率高得惊人（超时、404、
 * 403、被重定向到登录页、返回乱码……），每一种都需要不同的处置方式，
 * 而它们都是<b>预期内</b>的结果。用异常表达会迫使调用方在 catch 块里再区分
 * 十来种情况，最终写成一个 switch —— 那还不如直接把结果做成数据。
 *
 * <p>三类结果必须分开，因为处置方式完全不同：
 * <table border="1">
 *   <caption>结果分类</caption>
 *   <tr><th>判定</th><th>HTTP</th><th>处置</th></tr>
 *   <tr><td>{@link #isNotModified()}</td><td>304</td><td>内容未变，置完成，<b>不重抓正文、不重送审核</b></td></tr>
 *   <tr><td>{@link #isSuccess()}</td><td>2xx</td><td>正常抽取</td></tr>
 *   <tr><td>{@link #isRetryable()}</td><td>5xx / 429 / 网络错误</td><td>退避后重试（429 优先用服务端给的 Retry-After）</td></tr>
 *   <tr><td>{@link #isPermanentFailure()}</td><td>404 / 410 / 400</td><td><b>直接置跳过，不重试</b></td></tr>
 * </table>
 *
 * <p>把 404 归入「永久失败」而不是重试是必须的：死链是采集里最大的一类噪音，
 * 对它们重试三次意味着把三倍的时间花在确定不存在的页面上，还会让对方站点
 * 在日志里看到一串对我们域名的重复 404（这是「不礼貌爬虫」的典型特征）。
 */
@Getter
public final class PageFetchResult {

    /** 未收到响应（连接失败、超时、DNS 解析失败）。 */
    public static final int NO_RESPONSE = -1;

    /** HTTP 状态码；{@link #NO_RESPONSE} 表示压根没拿到响应。 */
    private final int httpStatus;

    /** 响应体（仅 2xx 且内容类型是 HTML 时才有值）。 */
    private final String body;

    /** 响应内容类型。用于拦下 {@code application/pdf} 这类伪装成 .html 的资源。 */
    private final String contentType;

    /** 最终 URL。**可能因为重定向与请求的不同**，是判定「是否跳出了种子范围」的依据。 */
    private final String finalUrl;

    private final String etag;

    private final String lastModified;

    /** 失败原因，人类可读。成功时为 null。 */
    private final String errorMessage;

    /**
     * 服务端要求的重试等待秒数（{@code Retry-After} 头）。
     *
     * <p>为 null 表示服务端没提要求，用本地退避策略。
     * <b>非 null 时必须以它为准</b> —— 这是服务端唯一能直接告诉我们「别来」的信号，
     * 忽略它等于明确表态不读对方的限流意图。
     */
    private final Long retryAfterSeconds;

    private PageFetchResult(int httpStatus, String body, String contentType, String finalUrl,
                            String etag, String lastModified, String errorMessage, Long retryAfterSeconds) {
        this.httpStatus = httpStatus;
        this.body = body;
        this.contentType = contentType;
        this.finalUrl = finalUrl;
        this.etag = etag;
        this.lastModified = lastModified;
        this.errorMessage = errorMessage;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** 成功拿到 HTML 正文。 */
    public static PageFetchResult success(int status, String body, String contentType, String finalUrl,
                                          String etag, String lastModified) {
        return new PageFetchResult(status, body, contentType, finalUrl, etag, lastModified, null, null);
    }

    /** 304：条件请求命中，内容未变。 */
    public static PageFetchResult notModified(String finalUrl) {
        return new PageFetchResult(304, null, null, finalUrl, null, null, null, null);
    }

    /** 失败。{@code httpStatus} 用 {@link #NO_RESPONSE} 表示没拿到响应。 */
    public static PageFetchResult failure(int httpStatus, String finalUrl, String errorMessage,
                                          Long retryAfterSeconds) {
        return new PageFetchResult(httpStatus, null, null, finalUrl, null, null, errorMessage, retryAfterSeconds);
    }

    /** 是否命中条件请求的「未修改」分支。 */
    public boolean isNotModified() {
        return httpStatus == 304;
    }

    /** 是否拿到了可用的 HTML。 */
    public boolean isSuccess() {
        return httpStatus >= 200 && httpStatus < 300 && body != null;
    }

    /**
     * 是否值得重试。
     *
     * <p>429 单独列出来而不是归入「4xx 永久失败」：它是「现在别来」而不是「永远别来」，
     * 且服务端通常会给 Retry-After，是最应该被尊重的一类响应。
     */
    public boolean isRetryable() {
        if (httpStatus == NO_RESPONSE) {
            return true;
        }
        if (httpStatus == 429) {
            return true;
        }
        return httpStatus >= 500 && httpStatus < 600;
    }

    /**
     * 是否属于「重试也没用」的失败。
     *
     * <p>401/403 归入这一类是有意的：它们通常意味着站点有反爬或需要登录，
     * 重试不会有任何不同，继续重试只会加速被拉黑。此类页面的正确出路是
     * 把该站点从种子白名单里去掉（见 {@code docs/08-roadmap.md} 的降级策略），
     * 这不是采集器能自动决定的事。
     */
    public boolean isPermanentFailure() {
        if (httpStatus == NO_RESPONSE) {
            return false;
        }
        return httpStatus == 400 || httpStatus == 401 || httpStatus == 403
                || httpStatus == 404 || httpStatus == 405 || httpStatus == 410
                || httpStatus == 451;
    }

    /** 是否为重定向状态码。跟随重定向由 HTTP 客户端完成，这里只用于记录与排查。 */
    public boolean isRedirect() {
        return httpStatus >= 300 && httpStatus < 400 && httpStatus != 304;
    }
}
