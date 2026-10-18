package com.kuma.search.admin.security;

import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 登录接口的暴力破解防护：账号维度锁定 + 来源 IP 维度限流。
 *
 * <h3>两道闸各挡什么</h3>
 * <ul>
 *   <li><b>账号维度</b>：同一个用户名连续失败 N 次后锁定一段时间。
 *       挡的是"对着一个已知账号猛撞密码"。</li>
 *   <li><b>IP 维度</b>：同一个来源在时间窗内失败次数超限就拒绝。
 *       挡的是"拿一个密码字典去撞很多个账号，每个账号只撞 4 次"——
 *       这种打法账号维度永远不触发，只有 IP 维度能拦住。</li>
 * </ul>
 * 两者互补，单独任何一个都有明显的绕过路径，因此不能只留一个。
 *
 * <h3>只统计「失败」，不统计「尝试」</h3>
 * IP 维度计的是失败次数而非请求次数，是为了避免反向代理场景下的自伤：
 * 代理后面 {@code RemoteAddr} 是所有客户端共用的代理地址，
 * 若按请求数限流，正常的登录高峰会直接把这个共享的桶打满、让所有人登不进来。
 * 按失败数计，同样的场景下要把桶打满需要真的发生几十次密码错误。
 * 即便如此，<b>代理场景下这仍是一个共享的桶</b>，部署时必须让容器能还原真实客户端 IP，
 * 见 {@code docs/06-security.md} 的「来源 IP 的可信度」一节。
 *
 * <h3>为什么计数与过期必须原子地一起做</h3>
 * 若先 {@code INCR} 再单独 {@code EXPIRE}，两次调用之间进程退出/网络闪断，
 * 就会留下一个<b>没有 TTL 的计数 key</b> —— 它永远不会被清除，
 * 于是该账号被<b>永久</b>锁死，且只有人工删 key 才能恢复。
 * 用一段 Lua 脚本把两步合并成一次服务端原子执行，这个窗口就不存在了
 * （Redis 要么都执行，要么都不执行）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginAttemptGuard {

    /**
     * 计数并设置过期，仅在新创建时设置（固定窗口，而不是每次失败都续期）。
     *
     * <p>刻意<b>不</b>写成"每次失败都刷新 TTL"：那会变成滑动窗口，
     * 攻击者只要不停尝试就能让锁定永不结束，等于给了一个把账号钉死的开关。
     */
    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1])\n"
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n"
                    + "return c",
            Long.class);

    /** 用户名在 key 里的最大长度，与 {@code t_sys_user.username} 的列宽一致。 */
    private static final int MAX_USERNAME_KEY_LENGTH = 64;

    private final StringRedisTemplate stringRedisTemplate;
    private final KumaSearchProperties properties;

    /**
     * 登录前置检查：处于锁定/限流状态时直接拒绝。
     *
     * <p><b>必须在密码校验之前调用</b>。放在后面的话，被锁定的账号每来一次请求仍要跑一遍
     * BCrypt（强度 12 的散列是刻意设计成慢的），攻击者可以用它做 CPU 消耗型 DoS，
     * 同时"锁定"也失去了保护数据库和散列算力的意义。
     *
     * @throws BizException {@link ResultCode#RATE_LIMITED} 账号被锁定或来源 IP 超限；
     *                      {@link ResultCode#REDIS_ERROR} Redis 不可用（此时<b>拒绝</b>登录，
     *                      而不是放行 —— 理由见方法内的 catch 分支）
     */
    public void checkAllowed(String username, String clientIp) {
        KumaSearchProperties.Auth.Login cfg = loginConfig();
        if (!cfg.isGuardEnabled()) {
            return;
        }
        try {
            checkAccount(username, clientIp, cfg);
            checkIp(clientIp, cfg);
        } catch (BizException e) {
            // 锁定/限流本身就是业务异常，必须原样往上抛，不能被下面的兜底分支吞掉 ——
            // 吞掉的后果是"被锁定的账号反而能继续尝试"。这个 catch 的顺序是有意的，不要调换。
            throw e;
        } catch (RuntimeException e) {
            // 【fail-closed】Redis 不可用时拒绝登录，而不是放行。
            // 放行在本类里看着更"可用"，但它同时意味着：只要能让 Redis 出问题
            // （或者干脆等它出一次问题），防爆破这道闸就整个消失了，而攻击者完全知道这一点。
            // 反过来，拒绝登录的代价是"Redis 挂了期间没人能登录"，而这时
            // JwtAuthFilter 也已经把所有人挡在门外了，服务本来就不在可用状态。
            log.error("检查登录限制时 Redis 异常，按拒绝登录处理：{}", e.getMessage());
            throw BizException.of(ResultCode.REDIS_ERROR, "登录服务暂时不可用，请稍后重试", e);
        }
    }

    /** 账号维度：连续失败达到阈值即锁定。 */
    private void checkAccount(String username, String clientIp, KumaSearchProperties.Auth.Login cfg) {
        String accountKey = AuthKeys.loginFailureByAccount(normalize(username));
        Long failures = readCount(accountKey);
        if (failures != null && failures >= cfg.getMaxFailuresPerAccount()) {
            long remainingSeconds = remainingTtl(accountKey, cfg.getAccountLockSeconds());
            log.warn("登录被拒绝：账号已锁定 username={} ip={} 剩余={}秒", username, clientIp, remainingSeconds);
            throw BizException.of(ResultCode.RATE_LIMITED,
                    "登录失败次数过多，账号已被临时锁定，请 " + toMinutes(remainingSeconds) + " 分钟后再试");
        }
    }

    /** 来源维度：时间窗内失败达到阈值即拒绝。 */
    private void checkIp(String clientIp, KumaSearchProperties.Auth.Login cfg) {
        String ipKey = AuthKeys.loginFailureByIp(normalize(clientIp));
        Long failures = readCount(ipKey);
        if (failures != null && failures >= cfg.getMaxFailuresPerIp()) {
            long remainingSeconds = remainingTtl(ipKey, cfg.getIpWindowSeconds());
            log.warn("登录被拒绝：来源 {} 失败次数超限，剩余={}秒", clientIp, remainingSeconds);
            throw BizException.of(ResultCode.RATE_LIMITED,
                    "当前来源登录失败次数过多，请 " + Math.max(1, remainingSeconds) + " 秒后再试");
        }
    }

    /**
     * 记录一次登录失败。仅在校验失败时调用，成功不调用。
     *
     * <p>这里不区分"用户名不存在"与"密码错误"——两者都要计数，
     * 否则攻击者能拿计数是否增长来反推账号是否存在。
     */
    public void recordFailure(String username, String clientIp) {
        KumaSearchProperties.Auth.Login cfg = loginConfig();
        if (!cfg.isGuardEnabled()) {
            return;
        }
        increment(AuthKeys.loginFailureByAccount(normalize(username)), cfg.getAccountLockSeconds());
        increment(AuthKeys.loginFailureByIp(normalize(clientIp)), cfg.getIpWindowSeconds());
    }

    /**
     * 登录成功后清除账号维度的失败计数。
     *
     * <p>不清除的话，用户"错 4 次、对了、过一会儿又错 1 次"会被累计锁死 ——
     * 那是把忘记密码的正常用户和撞库的攻击者一起惩罚。
     *
     * <p>刻意<b>不</b>清除 IP 维度的计数：同一个 IP 换一个用户名登录成功，
     * 不应该成为它此前大量失败记录的免罪符。
     */
    public void reset(String username) {
        if (!loginConfig().isGuardEnabled()) {
            return;
        }
        stringRedisTemplate.delete(AuthKeys.loginFailureByAccount(normalize(username)));
    }

    // ==================================================================================
    //  内部实现
    // ==================================================================================

    private KumaSearchProperties.Auth.Login loginConfig() {
        return properties.getAuth().getLogin();
    }

    private void increment(String key, int ttlSeconds) {
        try {
            stringRedisTemplate.execute(INCR_WITH_TTL, List.of(key), String.valueOf(ttlSeconds));
        } catch (RuntimeException e) {
            // Redis 不可用时这里只记录不抛出：登录失败的计数丢失会削弱防爆破，
            // 但因此让正常用户登不进来是更糟的结果（可用性优先于这一层的强度）。
            // 注意这是**已知的有意降级**，Redis 长期不可用意味着这道防线实际是关的。
            log.error("记录登录失败次数失败（Redis 异常），防爆破计数可能不准：{}", e.getMessage());
        }
    }

    private Long readCount(String key) {
        String raw = stringRedisTemplate.opsForValue().get(key);
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取剩余 TTL；值为 -1（无 TTL）或 -2（key 不存在）时退回配置值，避免把 0 当结果。 */
    private long remainingTtl(String key, int fallbackSeconds) {
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        return (ttl == null || ttl <= 0) ? fallbackSeconds : ttl;
    }

    private long toMinutes(long seconds) {
        return Math.max(1, (seconds + 59) / 60);
    }

    /**
     * 规范化用作 key 的片段。
     *
     * <p>截断长度是为了防止有人用一个超长用户名（或伪造一个超长 {@code RemoteAddr}）
     * 反复制造大 key 撑爆 Redis 内存。取不到值时用 {@code unknown} 占位，
     * 保证 key 结构稳定、不会退化成"前缀后面什么都没有"。
     */
    private String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String trimmed = raw.trim();
        return trimmed.length() > MAX_USERNAME_KEY_LENGTH
                ? trimmed.substring(0, MAX_USERNAME_KEY_LENGTH)
                : trimmed;
    }
}
