package com.kuma.search.admin.security;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 认证相关 Redis key 的唯一构造入口。
 *
 * <p>与 {@code CacheKeys} 同样的理由：key 一旦在多个类里各拼一遍，
 * 改前缀时漏改一处不会报任何错，只会让「黑名单/锁定期」静默失效 ——
 * 表现为登出后令牌仍然能用，而这种失效没有任何日志会告诉你。
 *
 * <p>统一前缀 {@code kuma:auth:}，与业务缓存前缀 {@code kuma:} 分开，
 * 便于运维区分「认证态数据」（可安全清空，代价是所有人重新登录）与缓存数据。
 *
 * <h3>这些 key 的容量</h3>
 * 全部带 TTL，且 TTL 都取对应令牌的自然有效期或锁定窗口，
 * 因此不需要额外的清理任务：令牌过期后记录自动消失，Redis 不会随运行时间无限增长。
 */
public final class AuthKeys {

    private AuthKeys() {
    }

    /** 认证数据前缀。 */
    public static final String PREFIX = "kuma:auth:";

    /**
     * 单令牌黑名单（登出 / 刷新轮换时写入），value 固定为 {@code "1"}。
     *
     * <p>按 {@code jti} 而不是按令牌串本身做 key：令牌串长达数百字符，
     * 直接当 key 既浪费内存，也会让 {@code KEYS}/{@code SCAN} 的输出完全不可读。
     */
    public static String blacklist(String jti) {
        return PREFIX + "blacklist:" + jti;
    }

    /**
     * 用户级令牌作废水位线，value 为作废时刻的毫秒时间戳。
     *
     * <p>校验方式是拿令牌的 {@code iat} 与这个时间戳比大小：
     * 早于水位线的令牌一律失效。存时间戳而不是存一个自增版本号，是因为版本号方案
     * 一旦这个 key 过期就会退回初始值，导致「曾经被作废的用户」的合法令牌也被判为无效 ——
     * 而时间戳方案在 key 过期时正好是安全的（见 {@link TokenRevocationService} 的说明）。
     */
    public static String userRevoke(Long userId) {
        return PREFIX + "revoke:user:" + userId;
    }

    /**
     * 账号维度的登录失败计数（暴力破解锁定）。
     *
     * <p>key 里放的是<b>用户提交的用户名原文</b>，而不是查库得到的 userId ——
     * 因为要防的正是「不存在但被反复尝试」的用户名，那时根本没有 userId 可用。
     */
    public static String loginFailureByAccount(String username) {
        return PREFIX + "login:fail:acct:" + username;
    }

    /**
     * 来源 IP 维度的登录失败计数（限流）。
     *
     * <p>与账号维度是两道互补的闸：只锁账号，攻击者可以拿一个字典去撞很多个账号（每个账号各 4 次）；
     * 只限 IP，攻击者可以换 IP 继续撞同一个账号。两个都要有。
     */
    public static String loginFailureByIp(String ip) {
        return PREFIX + "login:fail:ip:" + ip;
    }

    /**
     * 邮箱验证码，key 为 {@code kuma:auth:email:code:{scene}:{email}}。
     *
     * <p>存的是 <b>BCrypt 之后的散列</b>而不是明文。验证码只有 6 位数字、
     * 生命周期 5 分钟，看起来「泄露了也没关系」—— 但 Redis 的持久化文件（RDB/AOF）
     * 会被备份、会被运维拷走排查问题、可能被误提交进代码库。
     * 「一份快照泄露 = 一批正在生效的验证码泄露」这个等式不该成立，
     * 而让它不成立的代价只是校验时多做一次 BCrypt 比对。
     *
     * <p><b>{@code scene} 必须进 key。</b>注册与登录是两个独立场景，
     * 共用一个 key 会让「在注册页拿到的验证码」能用于登录另一个账号 ——
     * 这类跨场景重放是邮箱验证码实现里最经典的漏洞。
     */
    public static String emailCode(String scene, String email) {
        return PREFIX + "email:code:" + scene + ":" + email;
    }

    /**
     * 邮箱验证码的失败次数，key 为 {@code kuma:auth:email:code:try:{scene}:{email}}。
     *
     * <p>防的是「6 位数字 = 100 万种可能，5 分钟内暴力枚举」。
     * 达上限后立即删除验证码本身（而不是只拒绝本次），
     * 让攻击者必须重新触发发码 —— 而发码受同邮箱 60 秒节流与每日配额约束，
     * 于是爆破的速率被压到远低于 100 万 / 5 分钟。
     *
     * <p>TTL 与验证码一致：验证码过期后这个计数就没有意义了，
     * 留着它只会让下一次发码的验证码一上来就「已经失败过 5 次」。
     */
    public static String emailCodeAttempt(String scene, String email) {
        return PREFIX + "email:code:try:" + scene + ":" + email;
    }

    /**
     * 同邮箱发码节流，key 为 {@code kuma:auth:email:send:{email}}。
     *
     * <p>防的是「同一个邮箱被反复轰炸」。这不是安全问题而是体感问题 ——
     * 用户连点几次「获取验证码」就收到十几封邮件，接下来会把我们标记为垃圾邮件发送方，
     * 而发信域名的信誉一旦掉下去，所有用户的验证码都会进垃圾箱。
     */
    public static String emailSendThrottle(String email) {
        return PREFIX + "email:send:" + email;
    }

    /**
     * 单邮箱日发码配额，key 为 {@code kuma:auth:email:quota:{email}:{yyyyMMdd}}。
     *
     * <p>日期进 key 而不是用「当天 24 点过期」的 TTL：RDB 恢复、时钟调整、
     * 跨时区部署都会让「当天 24 点」这个时刻算错，而日期后缀在跨天时自然失效，
     * 不依赖任何时钟运算。代价是当天剩下的旧 key 要多占一天的内存（无 TTL 到时清理，
     * 见 {@link #quotaTtlSeconds()} 的说明）。
     */
    public static String emailDailyQuota(String email, String dateSuffix) {
        return PREFIX + "email:quota:" + email + ":" + dateSuffix;
    }

    /**
     * 全局日发码配额，key 为 {@code kuma:auth:email:quota:global:{yyyyMMdd}}。
     *
     * <p><b>这是唯一一道防「额度被烧穿」的闸。</b>前两道（同邮箱 60 秒、单邮箱日 10 封）
     * 都拦不住「换邮箱」的分布式刷量 —— 攻击者每换一个邮箱就是一个全新的计数桶。
     *
     * <p>额度对齐阿里云 DirectMail 的免费档（2000 封总量 / 200 封每日），
     * 超出即按量计费。默认上限 150 留了余量给运维手工发信，
     * 见 {@code kuma.auth.email.global-daily-limit} 与 {@code docs/07-deployment.md} §十二。
     */
    public static String emailGlobalQuota(String dateSuffix) {
        return PREFIX + "email:quota:global:" + dateSuffix;
    }

    /**
     * 配额类 key 的 TTL（秒），固定 36 小时。
     *
     * <p>为什么不是「到当天 24 点」：那需要算时区、算夏令时，算错的表现是
     * 「某天配额不清零」或「刚发两封就说超限」。36 小时这个固定值保证
     * 任何一天写入的 key 都会在次日（最迟后天）消失，而因为日期后缀进了 key，
     * 跨天之后新的一天必定从 0 开始计数 —— 也就是说 <b>TTL 只是清理手段，不参与限流逻辑</b>。
     * 这两种职责分离之后，TTL 算错也不会导致限流判错。
     */
    public static long quotaTtlSeconds() {
        return 36 * 3600L;
    }

    /**
     * 日期后缀，格式 {@code yyyyMMdd}。
     *
     * <p>由调用方传入日期而不是在这里取 {@code LocalDate.now()}：这样单测可以直接
     * 构造「昨天」「明天」的 key，不需要去 mock 系统时钟。
     */
    public static String dateSuffix(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    /**
     * 已消费的滑块凭证标记，key 为 {@code kuma:auth:captcha:used:{verificationHash}}。
     *
     * <p>滑块通过后前端会拿到一枚 {@code captchaVerification}，它会被随注册/登录/发码请求
     * 一起提交。这枚凭证<b>必须是一次性的</b>：否则同一个滑块结果可以在 120 秒内
     * 被重放任意多次，滑块的防护效果退化成"请求方需要先解一次滑块，之后随便发" ——
     * 而对自动化脚本来说，解一次滑块的边际成本无限接近零。
     *
     * <p>写入用 {@code SET NX}（见 {@code CaptchaVerifier}）：<b>判断与占用是同一次操作</b>。
     * 用「先 GET 判断、再 SET 写入」的写法，两个并发请求会双双读到"未使用"，
     * 而重放攻击本来就是并发行为。
     *
     * <p>key 里存的是凭证的散列而不是凭证本身：凭证串长度在数百字符量级，
     * 直接当 key 既浪费内存，也会让 {@code SCAN} 的输出完全不可读。
     */
    public static String captchaUsed(String verificationHash) {
        return PREFIX + "captcha:used:" + verificationHash;
    }
}
