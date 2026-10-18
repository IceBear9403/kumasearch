package com.kuma.search.admin.captcha;

import com.anji.captcha.model.common.CaptchaTypeEnum;
import com.anji.captcha.model.common.Const;
import com.anji.captcha.service.CaptchaService;
import com.anji.captcha.service.impl.CaptchaServiceFactory;
import com.kuma.search.config.KumaSearchProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 把 AJ-Captcha 装配进 Spring 上下文。
 *
 * <h3>⚠️ 为什么不用它的 starter，而是手写这一层</h3>
 * 上游的 {@code captcha-spring-boot-starter}（1.4.0 与 2.0.0 <b>都是</b>）
 * 基于 {@code spring-boot-starter-parent} 2.7.14 构建，自动配置写在
 * {@code META-INF/spring.factories} 里。而 Spring Boot 3 已经不再读取这个文件
 * （改为 {@code META-INF/spring/...AutoConfiguration.imports}）。
 *
 * <p>后果不是"启动报错"，而是<b>自动配置静默失效</b>：容器里根本没有
 * {@code CaptchaService}，注入处才失败；或者更隐蔽 —— 缓存类型退回内存实现，
 * 表现为"多实例下偶尔滑块过不去"。这类"依赖装上了、功能没生效"的问题，
 * 排查代价远高于自己写这 60 行装配代码。
 *
 * <p>只引核心包的实际代价很低，这一点是实测确认过的，不是推测：
 * 核心 jar 里既没有控制器、也没有任何 {@code javax/jakarta.servlet} 引用，
 * 它本来就是一个纯服务的库 —— 控制器由本项目的 {@code CaptchaController} 提供，
 * 因此不存在"路径冲突需要改配置"的问题。
 *
 * <h3>配置项从 {@code kuma.captcha.*} 翻译过来</h3>
 * 上游读的是一个 {@link Properties}，键名形如 {@code captcha.type}、
 * {@code captcha.slip.offset}（注意<b>不带</b> {@code aj.} 前缀 —— 那层前缀是
 * starter 的 {@code AjCaptchaProperties} 加的，不走 starter 就没有了）。
 * 这里把本项目的 {@code kuma.captcha.*} 逐个映射过去，好处是配置只有一个来源、
 * 命名遵循项目习惯，而不是在一个 yml 里混着两套风格迥异的键名。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kuma.captcha", name = "enabled", havingValue = "true")
public class AjCaptchaConfig {

    /** 支持的验证码类型。只有滑块拼图，理由见 {@code KumaSearchProperties.Captcha#type}。 */
    private static final Set<String> SUPPORTED_TYPES =
            Set.of(CaptchaTypeEnum.BLOCKPUZZLE.getCodeValue());

    /** 支持的缓存类型。{@code local} 只允许单实例部署时使用，见下方自检。 */
    private static final Set<String> SUPPORTED_CACHE_TYPES = Set.of("redis", "local");

    private final KumaSearchProperties properties;

    public AjCaptchaConfig(KumaSearchProperties properties) {
        this.properties = properties;
    }

    /**
     * 构造上游需要的 {@code CaptchaService}。
     *
     * <p>上游的工厂方法会顺带调用 {@code init(config)}，把底图与字体加载进内存
     * （字体从 jar 内的 {@code fonts/} 读取，因此<b>不依赖宿主机安装中文字体</b>，
     * 也不需要在 Linux 上装 fontconfig）。这个动作只做一次。
     */
    @Bean
    public CaptchaService captchaService(StringRedisTemplate stringRedisTemplate) {
        KumaSearchProperties.Captcha captcha = properties.getCaptcha();

        // 绑定必须在 getInstance 之前：上游 init() 里会用缓存（local 分支会读 cacheNumber），
        // 而 ServiceLoader 造出来的那个实例只能通过静态字段拿到模板。
        if ("redis".equals(captcha.getCacheType())) {
            RedisCaptchaCacheService.bind(stringRedisTemplate);
        }

        Properties config = toUpstreamProperties(captcha);

        CaptchaService service;
        try {
            service = CaptchaServiceFactory.getInstance(config);
        } catch (RuntimeException e) {
            // 上游抛的是 "unsupported-[captcha.type]=xxx" 这种信息量很低的运行时异常。
            // 换成一条能直接指出配置项与合法取值的消息，否则运维要先去读它的源码。
            throw new IllegalStateException(
                    "滑块验证码初始化失败：kuma.captcha.type=" + captcha.getType()
                            + " 不受支持，合法取值 " + SUPPORTED_TYPES
                            + "。上游原始错误：" + e.getMessage(), e);
        }
        log.info("滑块验证码已启用：type={} cacheType={} slipOffset={}px aesStatus={}",
                captcha.getType(), captcha.getCacheType(),
                captcha.getSlipOffset(), captcha.isAesStatus());
        return service;
    }

    /**
     * 启动自检：把"配置写错"变成启动期就暴露的问题。
     *
     * <p>这里拦的都是<b>不加检查就会安静地跑到第一次请求才炸</b>的组合：
     * <ul>
     *   <li>{@code cache-type} 拼错（例如写成 {@code Redis}）—— 上游拿不到缓存实现，
     *       返回 null，第一次取图时 NPE；</li>
     *   <li>SPI 文件被删掉 —— 同上，而这是最容易在"清理无用文件"时被误删的一个；</li>
     *   <li>{@code local} 缓存 —— 单实例没问题，多实例会间歇性失败，
     *       而"间歇性"意味着不会有人在测试环境发现它。</li>
     *   <li>{@code aes-status=true} —— 它要求<b>浏览器端</b>用密钥把坐标加密后提交，
     *       而浏览器只肯给 AES-CBC/CTR/GCM，不肯给上游用的 AES-ECB，
     *       所以真打开它就必须在前端手写一份 AES。见下方说明。</li>
     * </ul>
     * 因此 {@code local} 只警告不阻断（本地开发确实只有一个实例），
     * 另外几项直接启动失败。
     */
    @PostConstruct
    void selfCheck() {
        KumaSearchProperties.Captcha captcha = properties.getCaptcha();

        if (!SUPPORTED_TYPES.contains(captcha.getType())) {
            throw new IllegalStateException(
                    "kuma.captcha.type=" + captcha.getType() + " 不受支持，合法取值 " + SUPPORTED_TYPES
                            + "。换成其它类型需要重写前端交互，不是改配置就能切的事。");
        }
        if (!SUPPORTED_CACHE_TYPES.contains(captcha.getCacheType())) {
            throw new IllegalStateException(
                    "kuma.captcha.cache-type=" + captcha.getCacheType() + " 不受支持，合法取值 "
                            + SUPPORTED_CACHE_TYPES + "（注意大小写，必须是全小写）。");
        }
        if (captcha.isAesStatus()) {
            // 实测的契约：aes-status=true 时上游会下发 secretKey，并要求客户端
            // 用它把坐标加密后提交（服务端用同一个 key 解密，见 PointVO/AESUtil）。
            // 本项目的前端刻意不做这件事，于是打开它的后果不是"更安全"，
            // 而是**所有滑块都过不去**：服务端拿明文去 AES 解密会直接抛异常，
            // 前端看到的是"验证码坐标解析失败"，而没有一个地方会提示这是配置问题。
            // 与其留一个一开就全坏的开关，不如在这里拒绝启动。
            throw new IllegalStateException(
                    "kuma.captcha.aes-status 只能是 false。打开它需要浏览器端用 AES-ECB 加密坐标"
                            + "（上游用的是 ECB，而 Web Crypto 不提供 ECB，只能手写实现），"
                            + "本项目未实现该模式。它也不构成安全能力：密钥随取图响应一起下发，"
                            + "对客户端必然可见，真正的判定始终是服务端比对坐标。"
                            + "请把 kuma.captcha.aes-status 改回 false。");
        }

        // 走到这里说明 SPI 已经加载过了（captchaService 这个 bean 先于本方法创建）
        var cache = CaptchaServiceFactory.getCache(captcha.getCacheType());
        if (cache == null) {
            throw new IllegalStateException(
                    "滑块验证码的缓存实现未注册：找不到 type()==" + captcha.getCacheType()
                            + " 的 CaptchaCacheService。若 cache-type=redis，"
                            + "请确认 META-INF/services/com.anji.captcha.service.CaptchaCacheService "
                            + "存在且内容正确（上游用 ServiceLoader 发现实现，不走 Spring 扫描）。"
                            + "已注册的实现：" + registeredCacheTypes());
        }

        if (captcha.getSlipOffset() <= 0 || captcha.getSlipOffset() > 30) {
            // 不阻断：这个值偏大只是"变弱"，不是"坏了"，而大范围的值仍然可用。
            // 但必须喊出来 —— 它直接等于猜中的难度，不会有人主动回看这个数。
            log.warn("kuma.captcha.slip-offset={} 超出建议范围 (0, 30]，滑块会明显变弱",
                    captcha.getSlipOffset());
        }

        if ("local".equals(captcha.getCacheType())) {
            log.warn("滑块验证码使用进程内缓存（cache-type=local）。"
                    + "这只在单实例部署下正确：多实例时取图与校验可能落在不同节点，"
                    + "表现为「偶尔提示验证码已失效」。生产务必改用 redis。");
        }
    }

    /** 已注册的缓存类型，只用于报错信息。 */
    private String registeredCacheTypes() {
        // CaptchaServiceFactory.cacheService 是 public static，但类型是原始 Map；
        // 这里只读取 keySet 用于展示，不做任何依赖其内容的逻辑判断。
        try {
            Set<String> types = new LinkedHashSet<>(CaptchaServiceFactory.cacheService.keySet());
            return types.isEmpty() ? "（一个都没有）" : types.toString();
        } catch (RuntimeException e) {
            return "（读取失败：" + e.getMessage() + "）";
        }
    }

    /** {@code kuma.captcha.*} → 上游的 {@code Properties}。 */
    private Properties toUpstreamProperties(KumaSearchProperties.Captcha captcha) {
        Properties config = new Properties();
        config.setProperty(Const.CAPTCHA_TYPE, captcha.getType());
        config.setProperty(Const.CAPTCHA_CACHETYPE, captcha.getCacheType());
        config.setProperty(Const.CAPTCHA_SLIP_OFFSET, String.valueOf(captcha.getSlipOffset()));
        config.setProperty(Const.CAPTCHA_WATER_MARK, captcha.getWaterMark());
        config.setProperty(Const.CAPTCHA_AES_STATUS, String.valueOf(captcha.isAesStatus()));
        config.setProperty(Const.CAPTCHA_INTERFERENCE_OPTIONS,
                String.valueOf(captcha.getInterferenceOptions()));
        config.setProperty(Const.CAPTCHA_CACAHE_MAX_NUMBER,
                String.valueOf(captcha.getCacheNumber()));
        config.setProperty(Const.CAPTCHA_TIMING_CLEAR_SECOND,
                String.valueOf(captcha.getTimingClearSeconds()));
        config.setProperty(Const.REQ_FREQUENCY_LIMIT_ENABLE,
                String.valueOf(captcha.isReqFrequencyLimitEnable()));

        // 底图与字体都用 jar 内自带的（const 里那四个 ORIGINAL_PATH_* 保持不设置，
        // 值为 null 时上游会从 classpath 的 defaultImages/ 与 fonts/ 加载）。
        // 【不要】在这里给它们设成空串以外的东西：一旦指向了文件系统路径，
        // 镜像里就多了一份必须随包分发的资源，而这份资源在打包时不会被校验。
        return config;
    }
}
