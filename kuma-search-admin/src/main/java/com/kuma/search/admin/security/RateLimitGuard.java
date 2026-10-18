package com.kuma.search.admin.security;

import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 匿名接口限流：按 IP（或其它维度）做固定窗口计数。
 *
 * <p>为什么需要它：搜索改成匿名可用之后，{@code POST /api/search} 就是一个
 * <b>无门槛、无上限的公开接口</b>，而它背后是 ES。没有任何限流意味着
 * 一个脚本就能让 ES 的负载涨到任意高度，而正常用户受影响的方式是「搜索结果变慢直到超时」。
 * 同一条链路上的 {@code /api/search/suggest} 更敏感 —— 前端每次击键都会触发它。
 *
 * <h3>⚠️ 与 {@link LoginAttemptGuard} 的一处刻意不同：这里是 fail-open</h3>
 * <table border="1">
 *   <caption>两个 Guard 在 Redis 故障时的相反取舍</caption>
 *   <tr><th></th><th>Redis 故障时</th><th>理由</th></tr>
 *   <tr>
 *     <td>{@link LoginAttemptGuard}</td>
 *     <td><b>fail-closed</b>：拒绝登录</td>
 *     <td>登录是安全动作。放行 = 防爆破这道闸在 Redis 抖动期间整个消失，
 *         而攻击者完全知道这一点，甚至可能主动制造抖动。</td>
 *   </tr>
 *   <tr>
 *     <td>本类</td>
 *     <td><b>fail-open</b>：放行并告警</td>
 *     <td>搜索是可用性动作。Redis 一抖就全站搜不了，代价远大于「短时失去限流」。
 *         而且搜索本身不涉及认证与数据变更，限流失效的最坏后果是「ES 被打慢」，
 *         与「账号被爆破」不是一个量级。</td>
 *   </tr>
 * </table>
 * <b>这个差异必须保留。</b>后人看到「一个 Guard 拒绝、另一个放行」很容易
 * 把它当成不一致的 bug「修正」掉，而两个方向的修正都会造成实际损害。
 *
 * <p>fail-open 的代价（Redis 长时间不可用 = 限流实际关闭）用<b>监控告警</b>兜底：
 * 每次降级都打 ERROR 日志，{@code kuma.rate-limit} 的降级次数应当有告警规则。
 *
 * <h3>阈值口径：按「前端一次操作发几个请求」来定</h3>
 * 搜索结果页会<b>并发</b>发两个请求（自有内容 + 网页采集）。
 * 若把两个接口合用一个 30 次/分钟的桶，用户点两次「下一页」就会被拦。
 * 因此 {@code web:ip} 与 {@code search:ip} 分开计数，各自独立。
 * 这是最容易在联调时被误判成「限流坏了」的地方。
 */
@Slf4j
@Component
public class RateLimitGuard {

    /**
     * 计数并设置过期。
     *
     * <p>与 {@link LoginAttemptGuard} 的脚本同形，但这里的 TTL 取 {@code 窗口 × 2}：
     * 因为窗口起点已经进了 key，同一个 key 只会被同一窗口的请求访问，
     * TTL 只是清理手段（见 {@link AuthKeys#quotaTtlSeconds()} 关于
     * 「TTL 不参与限流逻辑」的同一套思路）。给两倍窗口是为了容忍时钟小幅回拨 ——
     * 若 TTL 正好等于窗口，时钟往回跳会让新旧两个窗口的 key 同时存在但其中一个提前消失，
     * 计数被静默重置。
     */
    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1])\n"
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n"
                    + "return c",
            Long.class);

    /** key 片段的最大长度，与 {@link LoginAttemptGuard} 的取值保持一致。 */
    private static final int MAX_KEY_FRAGMENT_LENGTH = 64;

    private final StringRedisTemplate stringRedisTemplate;
    private final KumaSearchProperties properties;

    /**
     * 显式构造器，不用 {@code @RequiredArgsConstructor}。
     *
     * <p>不是风格偏好：Lombok 在这个类上与 IDE 的 Java 解析器存在已知的干扰
     * （注解处理器生成的构造器会被判成「需要 Java 25 的 Flexible Constructor Bodies」），
     * 而一个只有在特定 IDE 版本下才出现的编译错误，会让后续维护者花时间在
     * 「我的环境是不是坏了」上。显式写出来只多三行，换来的是「在任何环境里都一样」。
     * 项目里 {@code SearchServiceImpl} 也是同样的写法。
     */
    public RateLimitGuard(StringRedisTemplate stringRedisTemplate, KumaSearchProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    /**
     * 检查并计数。超限时抛异常，未超限时把计数加一。
     *
     * <p>检查与计数是<b>同一次 Redis 调用</b>（Lua 里的 INCR），
     * 因此不存在「两个并发请求都读到 29、都判定通过、结果放了 31 个进来」的窗口 ——
     * 而限流恰恰是那种并发下必然发生的场景。
     *
     * @param dimension     维度标识，取 {@link RateLimitKeys} 的 {@code DIM_*} 常量
     * @param key           区分值（通常是客户端 IP）
     * @param limit         窗口内允许的请求数；<b>非正数表示该维度不限流</b>，直接放行
     * @param windowSeconds 窗口长度（秒）
     * @throws BizException {@link ResultCode#RATE_LIMITED} 超限；
     *                      Redis 异常时<b>不抛</b>（fail-open），只记 ERROR 日志
     */
    public void check(String dimension, String key, int limit, int windowSeconds) {
        if (!properties.getRateLimit().isEnabled() || limit <= 0) {
            return;
        }
        int window = windowSeconds > 0 ? windowSeconds : properties.getRateLimit().getWindowSeconds();
        if (window <= 0) {
            window = 60;
        }

        long nowSeconds = System.currentTimeMillis() / 1000L;
        long windowStart = nowSeconds / window * window;
        String redisKey = RateLimitKeys.counter(dimension, normalize(key), windowStart);

        long count;
        try {
            Long result = stringRedisTemplate.execute(INCR_WITH_TTL,
                    List.of(redisKey), String.valueOf(window * 2L));
            if (result == null) {
                // 脚本返回 null 说明执行异常（Redis 会返回 null 而不是抛错的情况极少）。
                // 按 fail-open 处理，但记日志。
                log.error("限流计数返回空值，按放行处理: dimension={} key={}", dimension, key);
                return;
            }
            count = result;
        } catch (RuntimeException e) {
            // 【fail-open】见类注释。这里必须是 return 而不是 throw。
            log.error("限流检查失败（Redis 异常），本次放行 —— 该维度在此期间实际未受保护: "
                    + "dimension={} key={} err={}", dimension, key, e.getMessage());
            return;
        }

        if (count > limit) {
            long retryAfter = windowStart + window - nowSeconds;
            log.warn("限流命中: dimension={} key={} count={} limit={}/{}s",
                    dimension, key, count, limit, window);
            throw BizException.of(ResultCode.RATE_LIMITED,
                    "请求过于频繁，请 " + Math.max(1, retryAfter) + " 秒后再试");
        }
    }

    /**
     * 热词去重的标记逻辑<b>不在这里</b>，见 core 模块
     * {@code com.kuma.search.service.impl.HotWordServiceImpl#shouldCountHotWord}。
     *
     * <p>原因不是漏了：去重发生在「记录热词」这一步，而记录热词由 core 模块的
     * {@code SearchServiceImpl} 触发。core 不能依赖 admin，所以这段逻辑只能待在 core ——
     * 放在这里会逼出「两个模块各写一份 toRedisKey」的结果，
     * 而那正是 {@link RateLimitKeys} 这类类存在的意义所要消灭的问题。
     *
     * <p>本类只负责<b>请求频率</b>（拦住刷接口这件事本身），
     * 热词去重负责<b>数据写入</b>（即使请求放行了，同一个词也不重复计数）。两层互补，都不多余。
     */

    /**
     * 规范化用作 key 的片段。
     *
     * <p>与 {@link LoginAttemptGuard#normalize} 同一套做法（长度截断 + 空值占位）：
     * 一个超长的 {@code RemoteAddr} 或伪造的 XFF 值可以反复制造大 key 撑爆 Redis 内存，
     * 而取不到值时用占位符能保证 key 结构稳定，不会退化成「前缀后面什么都没有」。
     *
     * <p>这个方法本身也是 public 的，供 {@code SearchController} 在需要
     * 「先算 key 再决定要不要计数」的场景复用，避免两处各写一份截断逻辑。
     */
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String trimmed = raw.trim();
        return trimmed.length() > MAX_KEY_FRAGMENT_LENGTH
                ? trimmed.substring(0, MAX_KEY_FRAGMENT_LENGTH)
                : trimmed;
    }
}
