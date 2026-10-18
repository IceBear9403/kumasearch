package com.kuma.search.crawl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按域名限速（礼貌爬取）。
 *
 * <p><b>这是「合规采集」与「把对方站点打挂」之间的分界线。</b>
 * 采集器天然是并发程序，而它对每个目标站点的「友好度」完全由这个类决定。
 * 种子站配置里的 {@code qps} 就是给它的（默认 0.5，即两秒一个请求）。
 *
 * <h3>为什么是「推迟」而不是「等待」</h3>
 * 拿不到配额时本类<b>不 sleep</b>，而是返回还差多少毫秒，由调用方把这个 URL 的
 * {@code next_fetch_time} 往后推。理由是 sleep 会占住一个采集线程 ——
 * 面对几十个不同 QPS 的站点，线程会被最慢的那个站全部占满，
 * 结果是对所有站点都变慢，而不只是对慢的那个。
 *
 * <h3>单域名并发上限 1 是怎么保证的</h3>
 * 不是靠锁，是靠调度方式：{@code CrawlScheduler} 按域名分组后<b>逐域名串行</b>处理
 * （同一域名的一批 URL 一次只发一个请求，等它结束再发下一个）。
 * 这样「并发数」退化成「顺序处理」，不需要额外的分布式锁，
 * 也不存在锁泄漏导致该域名永久卡死的问题。代价是整体吞吐受最慢站点限制，
 * 而这是有意的取舍 —— 采集速度本来就不是这个功能的目标。
 *
 * <p>限速状态放 Redis 而不是本地内存：多实例部署时每个实例单独计数，
 * 合起来就把对方站点的实际请求速率翻了好几倍 —— 而对方看到的是同一个来源 IP，
 * 不会因为「我们有三个实例」而宽容。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlRateLimiter {

    /**
     * 原子地「检查并占用」一个配额。
     *
     * <p>如果距离上次请求不足间隔，返回剩余等待毫秒；否则记录本次时刻并返回 0。
     *
     * <p><b>必须是 Lua 脚本。</b>先 {@code GET} 再判断再 {@code SET} 的三步写法，
     * 在两个线程同时进入时会双双读到「已过间隔」从而双双放行 ——
     * 而这个类的全部价值就在于这个判断的准确性，出现竞态等于没写。
     *
     * <p>{@code PX} 给的是 {@code 间隔 × 2}（下限 60 秒）：仅为在站点被下线、
     * 再没人访问它时让这个键自然消失，不必为清理再写一个任务。
     */
    private static final DefaultRedisScript<String> TRY_ACQUIRE = new DefaultRedisScript<>("""
            local last = redis.call('GET', KEYS[1])
            local now = tonumber(ARGV[1])
            local interval = tonumber(ARGV[2])
            if last then
              local elapsed = now - tonumber(last)
              if elapsed < interval then
                return tostring(interval - elapsed)
              end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', math.max(interval * 2, 60000))
            return '0'
            """, String.class);

    /** 两次请求之间的硬下限（毫秒）。见 {@link #intervalMillisOf(double)}。 */
    public static final long MIN_INTERVAL_MILLIS = 100L;

    private final StringRedisTemplate redisTemplate;

    /**
     * 尝试占用一次对 {@code domain} 的请求配额。
     *
     * @param domain      目标域名
     * @param qps         该站点的速率上限（次/秒），见 {@code t_crawl_seed.qps}
     * @return 允许本次请求时返回 0；否则返回「还需等待多少毫秒」。
     *         调用方应当据此推迟该 URL 的 {@code next_fetch_time}，而不是原地等待
     */
    public long tryAcquireMillis(String domain, double qps) {
        if (domain == null || domain.isBlank()) {
            return 0;
        }
        long intervalMillis = intervalMillisOf(qps);
        if (intervalMillis <= 0) {
            return 0;
        }
        try {
            String result = redisTemplate.execute(TRY_ACQUIRE,
                    List.of(CrawlKeys.domainRate(domain)),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(intervalMillis));
            long wait = result == null ? 0 : Long.parseLong(result);
            if (wait > 0) {
                log.debug("域名限速命中，推迟 {}ms: domain={} qps={}", wait, domain, qps);
            }
            return wait;
        } catch (RuntimeException e) {
            // fail-open。与 RateLimitGuard 同样的取舍，理由见那里的注释：
            // 采集是可用性动作，Redis 抖动就整站停采的代价大于「短时限速失效」。
            // 兜底是「不采集」会让 Redis 一挂采集就完全停摆，而采集本身没有对外承诺。
            log.warn("域名限速查询失败，本次放行: domain={} err={}", domain, e.getMessage());
            return 0;
        }
    }

    /**
     * 把 QPS 换算成「两次请求之间的最小间隔毫秒」。
     *
     * <p>取整用 {@code ceil}：{@code 0.7 QPS} 的真实间隔是 1428.6 毫秒，
     * 向下取整成 1428 会让实际速率略高于配置值 —— 在「限速」这个语境下，
     * 宁可慢一点点，也不要超出对方允许的速率。
     *
     * <p>同时设了「最小间隔 {@value #MIN_INTERVAL_MILLIS} 毫秒」的下限（即最多 10 QPS）：
     * 种子站配置里填 100 是很容易发生的手滑，而它意味着每秒 100 个请求打向同一个人，
     * 属于会立刻被拉黑的行为。这个下限防的是「配置能配出危险值」，不是防正常使用。
     */
    public long intervalMillisOf(double qps) {
        if (qps <= 0) {
            return 0;
        }
        long millis = (long) Math.ceil(1000.0 / qps);
        return Math.max(millis, MIN_INTERVAL_MILLIS);
    }

    /** 当前域名到「可以再次请求」还需多少毫秒。用于后台展示，不做占用。 */
    public long peekWaitMillis(String domain, double qps) {
        long intervalMillis = intervalMillisOf(qps);
        if (intervalMillis <= 0) {
            return 0;
        }
        try {
            String last = redisTemplate.opsForValue().get(CrawlKeys.domainRate(domain));
            if (last == null) {
                return 0;
            }
            long elapsed = System.currentTimeMillis() - Long.parseLong(last);
            return elapsed >= intervalMillis ? 0 : intervalMillis - elapsed;
        } catch (RuntimeException e) {
            log.debug("查询域名限速状态失败: domain={} err={}", domain, e.getMessage());
            return 0;
        }
    }
}
