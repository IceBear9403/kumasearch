package com.kuma.search.admin.captcha;

import com.anji.captcha.service.CaptchaCacheService;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;

/**
 * AJ-Captcha 挑战数据的 Redis 缓存实现。
 *
 * <h3>为什么必须自己写一个</h3>
 * 上游核心包只自带 {@code CaptchaCacheServiceMemImpl}（进程内 {@code ConcurrentHashMap}）。
 * 单节点部署时它没问题，但本项目是「Nginx + 后端」的形态，一旦后端起两个实例、
 * 负载均衡把取图请求分到 A、校验请求分到 B，B 找不到那份挑战数据，
 * 用户看到的是「验证码已失效，请重新获取」。这个失败<b>不会稳定复现</b> ——
 * 它取决于请求落在哪个节点，表现为"偶尔滑块过不去"。用户会以为是网络问题、
 * 运维会先去查前端，而真正的原因是缓存没有共享。
 *
 * <h3>bean 是怎么被上游拿到的</h3>
 * 上游用的是 {@code ServiceLoader}（见 {@code CaptchaServiceFactory} 的静态块），
 * 只认 {@code META-INF/services/com.anji.captcha.service.CaptchaCacheService} 里登记的类，
 * <b>并调用无参构造器自行实例化</b> —— 也就是说它拿到的对象不受 Spring 管理，
 * 无法用构造器注入 Redis 模板。
 *
 * <p>因此这里用「静态字段 + 显式绑定」：{@link #bind} 由
 * {@link AjCaptchaConfig} 在装配 {@code CaptchaService} 时调用一次，之后
 * 上游拿到的那个 ServiceLoader 实例就能用上同一个模板。
 * 绑定发生在应用启动期、任何请求之前，因此不存在"用到时还没绑"的窗口。
 * 这是为了适配上游的扩展机制而做的妥协，不是随手写了个静态变量。
 *
 * <h3>key 为什么要再加一层前缀</h3>
 * 上游生成的 key 形如 {@code RUNNING:CAPTCHA:xxx} —— 没有项目前缀，
 * 在 {@code redis-cli --scan} 的输出里与其它应用的 key 完全分不开。
 * 本项目对 Redis key 的一贯要求是「前缀能说明它是什么、能不能安全清空」，
 * 因此统一加上 {@code kuma:captcha:}。好处在排查时立刻体现：
 * {@code SCAN kuma:captcha:*} 拿到的就是「正在进行中的滑块挑战」，
 * 清空它的代价只是让所有人重新滑一次，不涉及任何登录态 ——
 * 这与 {@code kuma:auth:}（清空代价是所有人重新登录）是不同的东西。
 *
 * <h3>Redis 异常时抛异常（fail-closed）</h3>
 * 与 {@code LoginAttemptGuard} 同向、与 {@code RateLimitGuard} 反向。
 * 判断依据还是那条：无法确认挑战数据是否有效，就等于无法排除伪造成立，
 * 这是安全判断而不是可用性问题。滑块是注册 / 登录 / 发码三条链路的<b>前置条件</b>，
 * 它失效时正确的行为是让这三条链路一起不可用，而不是放行。
 */
@Slf4j
public class RedisCaptchaCacheService implements CaptchaCacheService {

    /** 本实现使用的 key 前缀，见类注释。 */
    public static final String PREFIX = "kuma:captcha:";

    /**
     * 由 {@link AjCaptchaConfig} 在启动期注入的模板。
     *
     * <p>用 {@code volatile} 而不是普通静态字段：ServiceLoader 可能在
     * {@code CaptchaServiceFactory} 的静态块里被某个线程触发，
     * 而绑定发生在装配期，两者没有 happens-before 关系时读到的可能是 null。
     */
    private static volatile StringRedisTemplate redisTemplate;

    /**
     * 绑定 Redis 模板。由 {@link AjCaptchaConfig} 调用，不应在别处调用。
     *
     * @param template 项目统一的 {@code StringRedisTemplate}；为 null 时直接失败，
     *                 不让"没绑上"这个状态安静地存在
     */
    public static void bind(StringRedisTemplate template) {
        Objects.requireNonNull(template, "StringRedisTemplate 不能为 null");
        redisTemplate = template;
        log.info("滑块验证码的缓存实现已绑定到 Redis，key 前缀 {}", PREFIX);
    }

    /**
     * 取模板，未绑定或 Redis 不可用时抛异常。
     *
     * <p>这里刻意<b>不</b>返回 null 或降级到内存缓存：降级到内存会让
     * "多实例下间歇失败"这个已经很难查的问题变成"一部分请求走 Redis、
     * 一部分走内存"，失败模式更加不可复现。宁可整条链路一起报错。
     */
    private StringRedisTemplate require() {
        StringRedisTemplate template = redisTemplate;
        if (template == null) {
            log.error("滑块验证码缓存被使用，但尚未绑定 Redis 模板（应用启动期装配失败？）");
            throw BizException.of(ResultCode.SYSTEM_ERROR, "人机校验暂时不可用，请稍后重试");
        }
        return template;
    }

    /** 上游的 key 统一加上本项目前缀。 */
    private String key(String rawKey) {
        return PREFIX + rawKey;
    }

    @Override
    public void set(String key, String value, long expiresInSeconds) {
        try {
            if (expiresInSeconds > 0) {
                require().opsForValue().set(key(key), value, Duration.ofSeconds(expiresInSeconds));
            } else {
                // 上游对 TTL<=0 的语义是"不过期"。不设过期时间会留下永不清理的 key，
                // 但上游只在这种语义下传 0，照办即可 —— 擅自替换成一个默认 TTL
                // 会让"挑战争议数据"在用户还在滑动时就消失。
                require().opsForValue().set(key(key), value);
            }
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("写入", e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(require().hasKey(key(key)));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("查询", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            require().delete(key(key));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("删除", e);
        }
    }

    @Override
    public String get(String key) {
        try {
            return require().opsForValue().get(key(key));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("读取", e);
        }
    }

    /**
     * 原子自增，返回自增后的值。
     *
     * <p>用 {@code INCRBY} 而不是「先 GET 再 SET」：上游用它统计"验证失败次数"
     * （见 {@code AbstractCaptchaService#afterValidateFail}），
     * 而不是原子的话，并发的失败请求会各自读到旧值、各自写回，
     * 计数比实际少 —— 少的那部分正好是攻击者制造的并发量。
     */
    @Override
    public Long increment(String key, long val) {
        try {
            return require().opsForValue().increment(key(key), val);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("自增", e);
        }
    }

    @Override
    public void setExpire(String key, long expiresInSeconds) {
        try {
            require().expire(key(key), Duration.ofSeconds(expiresInSeconds));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("设置过期", e);
        }
    }

    /**
     * 类型标识。必须与 {@code kuma.captcha.cache-type} 的取值一致，
     * 否则上游找不到本实现、静默退回内存缓存。
     */
    @Override
    public String type() {
        return "redis";
    }

    /** 统一的 Redis 故障出口。文案不暴露内部细节，原因进日志。 */
    private BizException unavailable(String action, RuntimeException cause) {
        log.error("滑块验证码缓存{}失败（Redis 异常），按 fail-closed 拒绝本次请求: {}",
                action, cause.getMessage());
        return BizException.of(ResultCode.SYSTEM_ERROR, "人机校验暂时不可用，请稍后重试");
    }
}
