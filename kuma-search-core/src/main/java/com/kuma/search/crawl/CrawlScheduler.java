package com.kuma.search.crawl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.repository.entity.CrawlSeed;
import com.kuma.search.repository.entity.CrawlUrl;
import com.kuma.search.repository.mapper.CrawlSeedMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 采集调度：定期取一批待抓 URL，按域名分组后并行处理。
 *
 * <h3>为什么按域名分组并行，而不是直接并行</h3>
 * 并行度必须按<b>域名</b>而不是按<b>URL</b> 来切。直接把 URL 丢进线程池，
 * 同一时刻可能有 4 个线程在请求同一个站点 —— 那么
 * {@link CrawlRateLimiter} 里那个「两次请求至少间隔 N 毫秒」的限制就形同虚设，
 * 因为它是「发请求前检查」而不是「排队等待」。
 *
 * <p>按域名分组后，每个域名只有<b>一个</b>任务在处理它自己那批 URL，串行发请求，
 * 限速的语义才成立。并行度体现在「同时有几个不同域名在被处理」，
 * 由 {@code kuma.crawl.concurrency} 控制。
 *
 * <h3>为什么整轮结束才取下一批</h3>
 * 用 {@code invokeAll} 等整轮完成，而不是「取一批就立刻再取一批」。
 * 后者会让积压的域名不断抢占线程，而已经开工的域名被挤到后面 ——
 * 结果是所有域名都变慢，且失败重试的时机被打乱。
 * 采集不是延迟敏感的任务，稳定压倒吞吐。
 *
 * <h3>关于多实例部署</h3>
 * <b>{@code kuma.crawl.enabled} 只应在一个实例上打开。</b>两个实例同时开时：
 * <ul>
 *   <li><b>不会重复抓同一页</b> —— {@code claimBatch} 的 {@code WHERE status=0} 是行级互斥，
 *       同一行不会被两个实例双双认领。这一点是安全的。</li>
 *   <li><b>但按域名的限速会失效</b> —— Redis 里的限速游标虽然共享，
 *       两个实例各自「检查通过 → 发请求」之间没有互斥，因此对方会在同一瞬间
 *       收到两个请求。也就是说：限速的<b>平均</b>速率仍然正确，
 *       但瞬时速率会翻倍，而对方的反爬规则看的正是瞬时速率。</li>
 * </ul>
 * 正确的多节点方案是节点级互斥（{@code DistributedLock}）或把队列换成真正的分布式队列，
 * 见 {@code docs/08-roadmap.md}。本期不启用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlScheduler {

    private final KumaSearchProperties properties;
    private final CrawlSeedMapper crawlSeedMapper;
    private final CrawlFrontier crawlFrontier;
    private final CrawlPipeline crawlPipeline;
    private final UrlNormalizer urlNormalizer;

    /**
     * 抓取线程池。惰性创建。
     *
     * <p>队列容量刻意设为「无界」并配合 {@code CallerRunsPolicy}：
     * 提交的任务数受「本轮认领条数」限制（远小于队列溢出风险），
     * 而一旦真的溢出，让调用线程（调度线程）自己跑比丢弃任务正确 ——
     * 采集任务被静默丢弃意味着这些 URL 一直停在「抓取中」直到租约超时，
     * 白等五分钟。
     *
     * <p>线程名带上前缀，否则线上排查时看到的是一串 {@code pool-N-thread-M}，
     * 无法判断它属于哪个子系统。
     */
    private volatile ExecutorService pool;

    @PostConstruct
    void init() {
        if (!properties.getCrawl().isEnabled()) {
            log.info("网页采集未启用（kuma.crawl.enabled=false），调度器不会发起任何请求");
            return;
        }
        int concurrency = Math.max(1, properties.getCrawl().getConcurrency());
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "kuma-crawl-" + seq.getAndIncrement());
                // 非守护线程会在关闭时阻塞 JVM 退出；采集任务本身没有「必须跑完」的语义，
                // 被打断时 URL 会留在「抓取中」状态，由租约超时回收。
                t.setDaemon(true);
                return t;
            }
        };
        this.pool = new ThreadPoolExecutor(concurrency, concurrency,
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
        log.info("网页采集已启用: 并发域名数={} 批量={} 调度间隔={}ms",
                concurrency, properties.getCrawl().getBatchSize(),
                properties.getCrawl().getScanIntervalMillis());
    }

    @PreDestroy
    void shutdown() {
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                // 超时说明有抓取卡在网络上。{@code shutdownNow} 只中断本地线程，
                // 不会向目标站发送任何「取消」信号 —— 对方已经把请求收下了。
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    /**
     * 定时扫描。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：采集耗时不固定（受目标站响应速度影响），
     * {@code fixedRate} 会让任务堆积，而采集的堆积没有任何意义 ——
     * 队列里的 URL 本来就在等着，多排几轮不会更快。
     */
    @Scheduled(fixedDelayString = "${kuma.crawl.scan-interval-millis:30000}")
    public void scheduledScan() {
        if (!properties.getCrawl().isEnabled()) {
            return;
        }
        try {
            scanOnce();
        } catch (RuntimeException e) {
            // 定时任务里逃出去的异常会被 Spring 记日志，但不会阻止下一轮执行。
            // 这里自己接住并记下来，是为了让日志里出现的是「这一轮失败了」这个人能看懂的信息，
            // 而不是一层层的堆栈。
            log.error("采集调度轮次失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 一轮扫描的结果摘要。
     *
     * <p>四个数字合起来正好能回答「爬虫为什么什么都不干」这个问题的四种原因，
     * 因此它是排障的第一手材料，而不是给人看的装饰：
     * <ul>
     *   <li>{@code seedEnqueued = 0 且 claimed = 0} → 没有启用中的种子站，或它的入口 URL 不合法（看 WARN 日志）；</li>
     *   <li>{@code seedEnqueued > 0 且 claimed = 0} → 种子刚入队，下一轮才会被认领（正常）；</li>
     *   <li>{@code recovered > 0} → 上一轮有任务没正常收尾（进程被杀 / 抓取卡死），
     *       这些 URL 会被重新放回待抓，不是故障；</li>
     *   <li>{@code claimed > 0} → 真的抓了，成功失败要看 {@code t_crawl_url.status}。</li>
     * </ul>
     *
     * @param claimed      本轮认领的待抓 URL 条数
     * @param recovered    本轮回收的过期租约条数
     * @param seedEnqueued 本轮由种子站入口 URL <b>新入队</b>的条数（已存在的不会重复计数）
     * @param domains      本轮实际处理的域名个数
     */
    public record ScanSummary(int claimed, int recovered, int seedEnqueued, int domains) {
    }

    /**
     * 执行一轮扫描。抽成公开方法便于手工触发与单测（{@code @Scheduled} 的调用点不可测）。
     */
    public ScanSummary scanOnce() {
        // 1) 先回收租约。放在最前面：崩溃后留下的「抓取中」记录越早回收越早能重试，
        //    而这一步是幂等的，每轮都跑没有副作用。
        int recovered = crawlFrontier.recoverExpiredLeases(properties.getCrawl().getLeaseTimeoutSeconds());

        // 2) 确保每个启用中的种子都被激活。enqueue 是幂等的，
        //    因此每轮都调一次的成本只是一次重复键检查，换来的是「新增种子站不需要重启」。
        int seedEnqueued = bootstrapSeeds();

        // 3) 取任务
        List<CrawlUrl> claimed = crawlFrontier.claim(properties.getCrawl().getBatchSize());
        if (claimed.isEmpty()) {
            return new ScanSummary(0, recovered, seedEnqueued, 0);
        }

        // 4) 按域名分组，每组一个任务
        Map<String, List<CrawlUrl>> byDomain = new LinkedHashMap<>();
        for (CrawlUrl url : claimed) {
            // domain 为空的记录不该存在（入队时必然有 host），但真出现了也不能让它污染分组，
            // 用一个固定桶收着，至少能被执行到并留下错误记录。
            String domain = url.getDomain() == null ? "_unknown_" : url.getDomain();
            byDomain.computeIfAbsent(domain, k -> new ArrayList<>()).add(url);
        }

        List<Callable<Void>> tasks = new ArrayList<>(byDomain.size());
        for (Map.Entry<String, List<CrawlUrl>> entry : byDomain.entrySet()) {
            List<CrawlUrl> group = entry.getValue();
            tasks.add(() -> {
                // 同一域名内严格串行，见类注释
                for (CrawlUrl url : group) {
                    crawlPipeline.process(url);
                }
                return null;
            });
        }

        try {
            pool.invokeAll(tasks);
        } catch (InterruptedException e) {
            // 关闭流程。恢复中断标志让上层能正常退出，本轮的剩余 URL 交给租约超时回收。
            Thread.currentThread().interrupt();
            log.info("采集轮次被中断，剩余 URL 将在租约超时后被回收");
        }

        log.info("采集轮次完成: 认领={} 域名数={} 回收租约={} 种子新入队={}",
                claimed.size(), byDomain.size(), recovered, seedEnqueued);
        return new ScanSummary(claimed.size(), recovered, seedEnqueued, byDomain.size());
    }

    /**
     * 手工触发一轮扫描（管理后台的「立即扫描一次」）。
     *
     * <p>与 {@link #scheduledScan()} 的两点差别都是刻意的：
     * <ul>
     *   <li><b>不吞异常</b> —— 手工触发是为了立刻看到失败原因，把异常吞掉再返回
     *       一个"本轮 0 条"等于什么也没告诉调用方；</li>
     *   <li><b>开关关闭时明确报错</b>，而不是像定时任务那样静默 return。
     *       静默 return 在这里会被读成"扫了，但一条都没抓到"，
     *       于是有人会去查种子站配置 —— 而真正的原因只是一个没打开的开关。</li>
     * </ul>
     *
     * <p>用 {@code pool == null} 而不是再查一次配置项判断开关：线程池只在启用时才创建，
     * 而下一行的 {@code invokeAll} 正好依赖它非空 —— 两件事用同一个判断，
     * 就不可能出现"校验说启用了、执行时 NPE"的缝隙。
     */
    public ScanSummary scanOnceManually() {
        if (pool == null) {
            throw BizException.of(ResultCode.SYSTEM_ERROR,
                    "网页采集未启用（kuma.crawl.enabled=false），扫描已跳过。"
                            + "开启该开关并重启后再试");
        }
        return scanOnce();
    }

    /**
     * 把启用中的种子站的入口 URL 入队。
     *
     * <p>每轮都做，因为 {@code enqueue} 对已存在的 urlHash 返回 false 且不做任何修改，
     * 所以重复调用的代价只是一次 INSERT 尝试 + 一次唯一键冲突。
     * 这个代价换来的是「后台加一个种子站，下一轮就开始采」——
     * 不需要重启进程，也不需要额外的「激活」操作（那个操作迟早会被人忘掉）。
     */
    private int bootstrapSeeds() {
        LambdaQueryWrapper<CrawlSeed> wrapper = new LambdaQueryWrapper<CrawlSeed>()
                .eq(CrawlSeed::getStatus, CrawlSeed.STATUS_ENABLED);
        List<CrawlSeed> seeds = crawlSeedMapper.selectList(wrapper);
        // 「新入队了几条」而不是「有几个种子站」：前者才是"这次调度产生了新工作"的证据。
        // 每轮都在的种子站会让后者的数字一直不变，看不出任何东西。
        int enqueued = 0;
        for (CrawlSeed seed : seeds) {
            if (seed.getEntryUrl() == null || seed.getEntryUrl().isBlank()) {
                log.warn("种子站 {} 未配置入口 URL，已跳过", seed.getSeedName());
                continue;
            }
            // 相对路径、缺 scheme、带跟踪参数的入口都在这里被纠正或拒掉。
            // 返回 null 说明这个配置根本不可用，必须在日志里说清楚 ——
            // 否则现象只是「这个站一条都没采到」，排查要绕很远。
            String normalized = urlNormalizer.normalize(seed.getEntryUrl(), null);
            if (normalized == null) {
                log.warn("种子站 {} 的入口 URL 无法解析（需要完整的 http/https 地址）: {}",
                        seed.getSeedName(), seed.getEntryUrl());
                continue;
            }
            if (!urlNormalizer.inScope(normalized, seed.getDomain(), seed.allowsSubdomain())) {
                // 配了一个和 domain 不一致的入口。这是典型的配置错误，直接全部采不到，
                // 因此必须是 WARN。
                log.warn("种子站 {} 的入口 URL 不在其域名范围内，已跳过: entry={} domain={}",
                        seed.getSeedName(), normalized, seed.getDomain());
                continue;
            }
            if (crawlFrontier.enqueue(seed, normalized, null, 0)) {
                enqueued++;
            }
        }
        return enqueued;
    }
}
