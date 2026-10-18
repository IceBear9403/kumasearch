package com.kuma.search.crawl;

import com.kuma.search.repository.entity.CrawlSeed;
import com.kuma.search.repository.entity.CrawlUrl;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待抓队列的唯一入口。
 *
 * <p>与 {@code IndexEventPublisher} 是同一个思路：调用方只依赖这个接口，
 * 换成 Redis 队列 / Kafka / 独立采集服务时只替换实现，任务编排逻辑一行不动。
 *
 * <p><b>把它抽成接口的另一个（更实际的）理由</b>：当前实现是 MySQL 表 ——
 * 用数据库当队列在采集量不大时完全够用，还能白拿事务与可视化查询两个好处。
 * 但它的天花板是明确的（单表行数、状态更新的行锁竞争），
 * 到那时替换的成本应当只是一次「换实现」，而不是重写整个采集子系统。
 *
 * <h3>状态流转（实现必须严格遵守）</h3>
 * <pre>
 *   待抓(0) ──claim──&gt; 抓取中(1) ──┬── markDone ───────&gt; 已抓(2)      终态
 *                                 ├── markRetry ──────&gt; 待重试(3) ──┐
 *                                 │                                  │
 *                                 │        （到 next_fetch_time 后可被再次 claim）
 *                                 └── markTerminal ──&gt; 死信(4)/robots(5)/跳过(6)  终态
 *   抓取中(1) ──租约超时──&gt; 待抓(0)   ← 进程崩溃后由 recoverExpiredLeases 回收
 * </pre>
 *
 * <p>不允许出现「抓取中(1) 直接回到 待抓(0)」以外的跨状态跳转，
 * 也不允许从终态回到非终态：状态机一旦能被绕过，
 * 「这个 URL 到底采过没有」就再也无法回答，而越权重复采集是合规问题不是性能问题。
 */
public interface CrawlFrontier {

    /**
     * 认领一批待抓 URL。
     *
     * <p>认领会把状态置为「抓取中」并打上租约。实现必须保证<b>同一行不会被两个调用者认领</b>
     * —— 这是这个接口最重要的语义，其它都可以商量。
     *
     * @param batchSize 最多认领多少条
     * @return 认领到的 URL；没有可抓的返回空列表（不返回 null）
     */
    List<CrawlUrl> claim(int batchSize);

    /**
     * 把一个新发现的 URL 入队。
     *
     * <p>幂等：已存在的 urlHash 直接返回 false，不更新已有记录的状态。
     * 这一步很关键 —— 被发现的页面绝大多数是重复链接，
     * 「已存在就重置状态」会让 robots 禁止、死信、跳过的 URL 反复复活，
     * 每轮都重新抓一遍。
     *
     * @param seed           来源种子
     * @param normalizedUrl  归一化后的绝对 URL
     * @param parentUrlHash  发现它的页面；种子入口为 null
     * @param depth          层级
     * @return true 表示这是一条新 URL
     */
    boolean enqueue(CrawlSeed seed, String normalizedUrl, String parentUrlHash, int depth);

    /** 抓取成功。写入内容指纹与缓存协商字段（ETag / Last-Modified）。 */
    void markDone(CrawlUrl url, String contentHash, int httpStatus, String etag, String lastModified);

    /**
     * 抓取失败，安排重试。
     *
     * <p>实现应在此处应用退避：下一次的 {@code nextFetchTime} =
     * now + max(本地退避, 服务端 Retry-After)。<b>服务端的 Retry-After 优先</b>，
     * 它是对方唯一能直接表达「别来」的信号，无视它等于明确表态不读对方的限流意图。
     *
     * @param retryAfterSeconds 服务端要求的等待秒数；为 null 时用本地退避
     */
    void markRetry(CrawlUrl url, int httpStatus, String errorMessage, Long retryAfterSeconds);

    /**
     * 置为终态，不再重试。
     *
     * @param status 取值必须是 {@link CrawlUrl} 的终态常量之一
     *               （{@code STATUS_DEAD} / {@code STATUS_ROBOTS_DENIED} / {@code STATUS_SKIPPED}）；
     *               传非终态值是实现错误的信号，实现应当拒绝并记录
     */
    void markTerminal(CrawlUrl url, int status, String reason);

    /**
     * 把已认领的 URL 退回待抓，并推迟到 {@code waitMillis} 之后。
     *
     * <p>这是<b>域名限速的落点</b>：拿不到配额时不 sleep 占住线程，
     * 而是把它放回队列、推后时间，让线程去处理别的域名。
     *
     * <p><b>不递增 {@code retryCount}</b>：限速推迟不是失败，
     * 让它消耗重试次数会导致「站点访问量一大，一批正常 URL 集体进死信」。
     */
    void defer(CrawlUrl url, long waitMillis);

    /**
     * 把租约超时的「抓取中」记录退回待抓。
     *
     * @param leaseTimeoutSeconds 超过这个时长即视为原进程已死
     * @return 回收的条数。持续大于 0 说明有实例在反复崩溃，应当告警
     */
    int recoverExpiredLeases(int leaseTimeoutSeconds);

    /** 某个种子已成功抓取的页数，用于 {@code max_pages} 配额判定。 */
    int countFetched(Long seedId);

    /** 某个域名当前的待抓积压量，供后台展示。 */
    int countPending(String domain);

    /** 当前时间。抽出来是为了让时间相关的判定在单测里可控。 */
    LocalDateTime now();
}
