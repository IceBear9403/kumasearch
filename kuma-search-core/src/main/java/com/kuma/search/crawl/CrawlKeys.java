package com.kuma.search.crawl;

/**
 * 采集子系统的 Redis 键构造，唯一收口。
 *
 * <p>与 {@code CacheKeys} / {@code AuthKeys} / {@code RateLimitKeys} 同一套做法：
 * 键名格式散落在各处时，「线上有哪几个键、各是什么含义」这个问题就没人答得上来，
 * 而运维清理缓存时最怕的正是这个。
 *
 * <p><b>前缀单独开一个 {@code kuma:crawl:}</b>，不并入 {@code kuma:} 业务前缀。
 * 理由与 {@code kuma:rate:} 单独开一段相同：这几组键的「清空代价」完全不同 ——
 * 清 {@code kuma:crawl:robots:*} 只是让采集器重新拉一遍 robots.txt（无害），
 * 清 {@code kuma:} 业务缓存则会同时干掉搜索结果与热词榜。混在一起，
 * 运维就得在动手前先确认每个键属于哪一类，而没有人在紧急时愿意做这件事。
 *
 * <p>注意这里<b>没有</b>「种子页数计数」这类键：页数由
 * {@code CrawlUrlMapper.countFetchedBySeed} 实时统计。计数器与真实数据之间任何
 * 一次不一致都会永久跑偏（失败的回滚、手工改数据都会让计数失准），
 * 而这个统计走索引并不慢。
 */
public final class CrawlKeys {

    /** 采集子系统专用前缀。与 {@code kuma:auth:}、{@code kuma:rate:} 平级。 */
    public static final String PREFIX = "kuma:crawl:";

    private CrawlKeys() {
    }

    /**
     * robots.txt 原文缓存，值是该域名的 robots.txt 文本。
     *
     * <p>缓存原文而不是解析结果：解析结果是结构化对象，序列化进 Redis 需要额外的类型
     * 与版本管理（改了规则模型之后，缓存里的旧结构会在反序列化时炸掉），
     * 而重新解析一段文本是微秒级的操作。缓存的价值在「少发一个 HTTP 请求」，
     * 不在「少做一次解析」。
     */
    public static String robots(String domain) {
        return PREFIX + "robots:" + domain;
    }

    /**
     * robots.txt 不可用（5xx / 网络失败）的标记。
     *
     * <p>要与「没有 robots.txt」严格区分开：前者按规范应当临时禁采，
     * 后者应当放开采集。<b>把两者混成一个空值缓存，会在对方服务器抖动时
     * 把整站从「允许」翻成「允许」—— 也就是把该停的没停。</b>
     */
    public static String robotsUnavailable(String domain) {
        return PREFIX + "robots:down:" + domain;
    }

    /**
     * 域名维度的限速游标（上次请求该域名的时间戳，毫秒）。
     *
     * <p>用「记录上次请求时刻 + 睡够间隔」实现限速，而不是用一个令牌桶计数器。
     * 原因是按域限速的 QPS 低到 0.5 左右，用令牌桶要处理桶容量、补充速率、
     * 多线程竞争，而这里只有「两个请求之间至少隔 N 毫秒」一个语义。
     * 简单实现不容易写错，而这里写错的后果是请求过快被对方封 IP。
     */
    public static String domainRate(String domain) {
        return PREFIX + "rate:" + domain;
    }

    /** 种子站停采标记（页数达上限）。到时间自动清除，恢复采集由人工决定。 */
    public static String seedQuotaExhausted(Long seedId) {
        return PREFIX + "seed:quota:" + seedId;
    }
}
