package com.kuma.search.admin.security;

import com.kuma.search.config.KumaSearchProperties;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * 令牌吊销：单令牌黑名单 + 用户级作废水位线。
 *
 * <h3>为什么需要「用户级作废」这一层</h3>
 * 黑名单是按 {@code jti} 拉黑的，它能作废「手上这一枚」令牌，但作废不了「这个人已经发出去、
 * 但服务端还不知道 jti」的那些令牌。禁用账号、改密码、收回角色，都要求<b>立刻</b>踢掉该用户的
 * 所有令牌，而 access token 的有效期是 2 小时 —— 没有这一层，一个被禁用的人还能继续用旧令牌
 * 调接口最长 2 小时。这正是本类存在的理由。
 *
 * <h3>为什么是「水位线 + iat 比较」而不是「自增版本号」</h3>
 * 两种实现都能做到即时吊销，但版本号方案有一个不易察觉的坑：版本号 key 一旦过期，
 * 读取方拿到的就是"没有记录 = 版本 0"，于是所有携带旧版本号的令牌<b>全部失效</b>，
 * 表现为一次没有任何征兆的集体掉线；而如果为了回避它把 key 设成永不过期，
 * 又变成每个被作废过的用户永久占用一份数据。
 * 水位线方案没有这个问题：key 过期时，所有 {@code iat} 早于水位线的令牌本身也已经过期了。
 *
 * <h3>精度：为什么是「小于」而不是「小于等于」</h3>
 * JWT 的 {@code iat} 只精确到秒（这是规范决定的，不是实现选择），于是同一个自然秒内
 * 无法区分"作废前签发的令牌"和"作废后签发的令牌"。这里的比较是
 * {@code iat < 水位线}，含义是：<b>与作废同一秒内签发的令牌会被判为已作废</b>。
 * 这个方向是刻意选的 —— 多作废一点只是让用户重新登录，少作废一点就是漏洞。
 * 之所以不写 {@code iat <= 水位线}，是给"禁用后又立刻重新登录"留出余地，
 * 而不是给攻击者留窗口：被判失效的都是签发时间早于作废时刻的令牌。
 *
 * <h3>关于 TTL</h3>
 * 水位线的 TTL 取 refresh token 的有效期。可以这样取是因为：
 * 任何 {@code iat} 早于水位线的令牌，其自身过期时刻都不晚于「水位线 + refreshTtl」，
 * 所以 key 到期时，需要被它拦住的令牌已经一个都不剩了。
 * <b>不要把它改成更短的 TTL</b>，否则会留下一段"水位线已消失、旧令牌却还在"的窗口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRevocationService {

    private final StringRedisTemplate stringRedisTemplate;
    private final KumaSearchProperties properties;

    /**
     * 作废某个用户的全部令牌（签发时间早于此刻的）。
     *
     * <p>调用点应当是所有"用户的可信状态发生变化"的写操作：禁用账号、删除账号、
     * 改密码、变更角色或权限。这些操作之后继续持有旧令牌，都意味着权限模型失效。
     *
     * @param userId 用户 ID；为 null 时静默返回（无 userId 的令牌本就被判为无效）
     * @param reason 作废原因，只进日志，便于排查"用户说被莫名踢下线"
     */
    public void revokeUser(Long userId, String reason) {
        if (userId == null) {
            return;
        }
        long ttlSeconds = properties.getJwt().getRefreshTokenTtl();
        stringRedisTemplate.opsForValue().set(
                AuthKeys.userRevoke(userId), String.valueOf(System.currentTimeMillis()),
                ttlSeconds, TimeUnit.SECONDS);
        log.info("已作废用户 {} 的全部令牌，原因={}，有效期={}秒", userId, reason, ttlSeconds);
    }

    /**
     * 作废之后仍然可用的最早签发时刻（用于「作废 + 立刻换发新令牌」）。
     *
     * <h3>为什么不能直接用 {@code new Date()}</h3>
     * 水位线是<b>毫秒</b>（{@code System.currentTimeMillis()}），而 JWT 的 {@code iat}
     * 只有<b>秒</b>精度 —— 这是规范决定的，不是 jjwt 的选择。比较是 {@code iat < 水位线}，
     * 于是「刚刚作废、又用当前时刻签发一枚新令牌」会落进同一个自然秒里：
     * 新令牌的 {@code iat×1000} 小于水位线毫秒值，<b>被自己刚写的作废记录判为已作废</b>。
     * 表现是「改完密码，当前设备立刻掉线」，而用户刚看到「修改成功」。
     *
     * <p>因此重新签发的时刻必须推到水位线所在秒的<b>下一秒</b>。代价是令牌的有效期
     * 少不到 1 秒，与「用户被莫名踢下线」相比可以忽略。
     *
     * <p>与"作废同一秒内签发的旧令牌也会失效"这一点是同一套取舍的两面，
     * 见类注释的「精度」一节 —— 那里多作废一点，这里就得多等一秒。
     *
     * <p>取 `max(now, 该时刻)`：水位线可能是很久以前写的（比如用户三天前改过密码），
     * 直接用它签发会让 {@code exp = iat + ttl} 也落在过去，新令牌一出生就过期。
     *
     * @param userId 用户 ID；为 null 或没有水位线时返回当前时刻（无水位线 = 没有被作废过）
     */
    public Date issuableDateAfterRevoke(Long userId) {
        Date now = new Date();
        if (userId == null) {
            return now;
        }
        String raw = stringRedisTemplate.opsForValue().get(AuthKeys.userRevoke(userId));
        if (raw == null) {
            return now;
        }
        long watermark;
        try {
            watermark = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            // 值被写坏了。这时 isUserRevoked 会把该用户的**所有**令牌判为已作废
            // （包括这里将要签发的这一枚），所以签什么时刻都救不回来，只能让他重新登录。
            // 用当前时刻签发，至少错误信息是"令牌无效"而不是一个莫名其妙的过去时间。
            log.error("用户 {} 的作废水位线格式非法：{}，按当前时刻签发", userId, raw);
            return now;
        }
        long nextSecondMillis = (watermark / 1000L + 1L) * 1000L;
        return new Date(Math.max(now.getTime(), nextSecondMillis));
    }

    /**
     * 把单枚令牌加入黑名单（登出、刷新轮换时调用）。
     *
     * <p>TTL 取该令牌的<b>剩余有效期</b>而不是一个固定值：令牌自然过期后再拦住它没有任何意义，
     * 而按剩余时间设置可以让记录自动消失，黑名单的大小因此恒等于「最近登出/轮换过、
     * 且尚未自然过期」的令牌数，不随运行时间增长。
     *
     * @param claims 已解析且校验通过的令牌声明；缺少 jti 或过期时间时不写入
     *               （缺 jti 无法定位，过期则无需拦截）
     */
    public void blacklist(Claims claims) {
        if (claims.getId() == null || claims.getExpiration() == null) {
            return;
        }
        long remainingSeconds = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
        if (remainingSeconds <= 0) {
            return;
        }
        stringRedisTemplate.opsForValue().set(
                AuthKeys.blacklist(claims.getId()), "1", remainingSeconds, TimeUnit.SECONDS);
    }

    /**
     * 令牌是否在黑名单中（登出、刷新轮换会写入）。
     *
     * @return 无 {@code jti} 时返回 {@code true}：没有 jti 就无法吊销，
     *         视为不可用，杜绝"自己造一个不带 jti 的令牌来绕过黑名单"
     */
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return true;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(AuthKeys.blacklist(jti)));
    }

    /**
     * 该用户是否在 {@code issuedAt} 之后有过作废动作。
     *
     * @param issuedAt 令牌的 {@code iat}，秒级精度；为 null 时返回 {@code true}
     *                 （无从比较签发时间，按最保守处理）
     * @return 已被作废返回 {@code true}
     */
    public boolean isUserRevoked(Long userId, Date issuedAt) {
        if (userId == null || issuedAt == null) {
            return true;
        }
        String raw = stringRedisTemplate.opsForValue().get(AuthKeys.userRevoke(userId));
        if (raw == null) {
            return false;
        }
        try {
            return issuedAt.getTime() < Long.parseLong(raw);
        } catch (NumberFormatException e) {
            // 值被写坏了（人工改过 Redis、或写入被截断）。放行会留下一个永久有效的后门，
            // 拦截只是让这个用户重新登录一次 —— 因此按「已作废」处理。
            log.error("用户 {} 的作废水位线格式非法：{}，按已作废处理", userId, raw);
            return true;
        }
    }
}
