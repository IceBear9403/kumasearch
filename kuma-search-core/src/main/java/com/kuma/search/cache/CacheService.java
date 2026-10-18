package com.kuma.search.cache;

import java.util.function.Supplier;

/**
 * 缓存能力的抽象接口。
 *
 * <p>为什么要有这一层接口，而不是直接在业务里注入 {@code RedisTemplate}：
 * <ul>
 *   <li><b>换实现不改业务</b>：单机阶段用 Caffeine + Redis 两级；
 *       将来若要换成 Redis Cluster、或给某些 key 换成纯本地缓存，业务代码不动。</li>
 *   <li><b>把三个经典问题集中解决</b>：穿透、击穿、雪崩的应对逻辑写在实现里，
 *       业务方只需要调 {@link #get} 并传入「缓存没命中时怎么查」，
 *       不必在每个 service 里重复写一遍空值占位和互斥锁。</li>
 *   <li><b>可测</b>：单元测试里注入一个直接调用 loader 的空实现，就不需要起 Redis。</li>
 * </ul>
 */
public interface CacheService {

    /**
     * 读缓存，未命中则用 {@code loader} 回源并写回。
     *
     * <p>实现需要保证：
     * <ol>
     *   <li>回源返回 null 时写入<b>短 TTL</b> 的空值占位（防穿透）；</li>
     *   <li>同一 key 的并发回源只放一个请求穿透到下游（防击穿）；</li>
     *   <li>实际 TTL 加上随机抖动，避免大批 key 同时过期（防雪崩）。</li>
     * </ol>
     *
     * @param key    缓存 key，必须由 {@link CacheKeys} 构造
     * @param ttlSeconds 过期时间（秒），实际写入时会附加随机抖动
     * @param type   返回值的类型
     * @param loader 回源逻辑，返回 null 表示「确实没有」而非「查询失败」
     */
    <T> T get(String key, long ttlSeconds, Class<T> type, Supplier<T> loader);

    /** 写入缓存。 */
    void put(String key, Object value, long ttlSeconds);

    /**
     * 主动失效一个 key，并<b>广播</b>给其它实例清理各自的 L1。
     *
     * <p>漏掉广播这一步，二级缓存在多实例部署下就是错的 —— 详见 {@code TwoLevelCacheService} 的类注释。
     */
    void evict(String key);

    /** 按前缀批量失效，用于索引重建后清理某类型的全部检索缓存。 */
    void evictByPrefix(String prefix);

    /** 仅清本地 L1，供广播消息的接收方调用。 */
    void evictLocal(String key);
}
