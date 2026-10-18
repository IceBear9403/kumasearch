package com.kuma.search.admin.security;

/**
 * 限流相关 Redis key 的唯一构造入口。
 *
 * <p>与 {@link AuthKeys} 分开一个前缀（{@code kuma:rate:} 而不是 {@code kuma:auth:}），
 * 理由是<b>清空代价不同</b>。{@code kuma:auth:*} 是认证态数据，
 * 清空的后果是「所有人都要重新登录」—— 这是可接受的、且运维心里有数。
 * 限流计数器清空的后果完全不同（限流短时失效），而两类的 key 混在一起时，
 * 运维在动手前必须先逐个确认「这个 key 属于哪一类」，而没有人在紧急时愿意做这件事。
 *
 * <h3>窗口起点进 key，而不是每次重置计数器</h3>
 * key 形如 {@code kuma:rate:{dimension}:{key}:{windowStartEpochSecond}}。
 * 固定窗口限流有两种实现：一是「计数 + 到点清零」，二是「窗口起点进 key，老 key 自然过期」。
 * 这里选后者，因为它<b>没有清零这个动作</b> —— 没有清零就不需要判断「该不该清了」，
 * 也就不会出现「两个实例同时判断该清零、各自清一次」导致计数被重复重置的问题。
 * 代价是窗口边界处的流量可能瞬时翻倍（前一窗口的末尾与后一窗口的开头各放行一批），
 * 这是固定窗口的固有特性，对「防止单点把 ES 打爆」这个目标而言可以接受。
 */
public final class RateLimitKeys {

    /** 限流数据前缀。与 {@code kuma:}（业务）、{@code kuma:auth:}（认证）、{@code kuma:crawl:}（采集）平级。 */
    public static final String PREFIX = "kuma:rate:";

    /** 检索接口（自有内容）的 IP 维度。 */
    public static final String DIM_SEARCH_IP = "search:ip";
    /** 检索接口（网页采集）的 IP 维度。与自有内容<b>分开计数</b>。 */
    public static final String DIM_WEB_SEARCH_IP = "web:ip";
    /** 搜索建议的 IP 维度。 */
    public static final String DIM_SUGGEST_IP = "suggest:ip";
    /** 热词榜的 IP 维度。 */
    public static final String DIM_HOT_IP = "hot:ip";
    /** 可检索类型列表的 IP 维度。与热词榜同为"页面初始化时取一次"的轻量接口。 */
    public static final String DIM_TYPES_IP = "types:ip";
    /** 发送邮箱验证码的 IP 维度。 */
    public static final String DIM_EMAIL_CODE_IP = "email-code:ip";

    /**
     * 前台投稿的**用户**维度。
     *
     * <p>本类里唯一不是 IP 的维度，理由见
     * {@code KumaSearchProperties.RateLimit#contentSubmit}：投稿是已登录接口，
     * 而一个出口 IP 后面坐着几个人在隧道/NAT 下完全不可知 ——
     * 只按 IP 限流会误伤同一出口后的其他人，只按用户限流挡不住批量小号。
     */
    public static final String DIM_CONTENT_SUBMIT_USER = "content:submit:user";
    /** 前台投稿的 IP 维度。与上面的用户维度<b>成对出现、各自独立计数</b>，任一命中即拦。 */
    public static final String DIM_CONTENT_SUBMIT_IP = "content:submit:ip";

    /**
     * 自助改密码的用户维度。防"拿着令牌暴力猜原密码"。
     *
     * <p>它要挡的是「某个账号的原密码是什么」，而这件事只与账号有关 ——
     * 配一个 IP 维度没有意义（正常用户可能共用出口），因此这一档只按用户计数。
     */
    public static final String DIM_PROFILE_PASSWORD_USER = "profile:password:user";
    /**
     * 换绑邮箱的用户维度。防"反复触发换绑去试探哪些邮箱可用"。
     *
     * <p>邮箱本身还有发码侧的节流与日配额（见 {@code EmailCodeService}），
     * 这一档限制的是"换绑这个动作"的频率，两道闸管的不是同一件事。
     */
    public static final String DIM_PROFILE_EMAIL_USER = "profile:email:user";

    private RateLimitKeys() {
    }

    /**
     * 窗口计数器 key。
     *
     * @param dimension            维度标识，取本类的 {@code DIM_*} 常量
     * @param key                  该维度内的区分值（IP 或邮箱等）
     * @param windowStartEpochSecond 当前窗口的起始秒。由 {@link RateLimitGuard} 按
     *                             {@code now / windowSeconds * windowSeconds} 算出，
     *                             保证同一窗口内的所有请求拼出同一个 key
     */
    public static String counter(String dimension, String key, long windowStartEpochSecond) {
        return PREFIX + dimension + ":" + key + ":" + windowStartEpochSecond;
    }

    /**
     * 热词去重的 key <b>不在这里</b>，见 core 模块的
     * {@code com.kuma.search.cache.CacheKeys#hotDedup}。
     *
     * <p>原因不是漏了：去重发生在「记录热词」这一步，而记录热词在
     * {@code SearchServiceImpl}（core 模块）里，core 不能依赖 admin。
     * 硬把它塞进这个类只会逼出两种结果之一 —— 引入一个反向的模块依赖，
     * 或者两个模块各写一份 key。后者正是这几个 Keys 类存在的意义所要消灭的问题。
     */
}
