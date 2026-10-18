package com.kuma.search.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kuma.search.config.KumaSearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 二级缓存实现：L1 = Caffeine（进程内），L2 = Redis（跨进程共享）。
 *
 * <h3>为什么要两级</h3>
 * 热点搜索词的 QPS 往往占总量的一半以上。每次查询都走一次 Redis 网络往返，
 * 在 500 QPS 目标下会成为不必要的延迟与连接数开销。L1 把这类热点挡在进程内，
 * 单次查询的缓存命中开销降到纳秒级；L2 负责跨实例共享与容量兜底。
 *
 * <h3>多实例部署下必须守住的边界</h3>
 * L1 是<b>每个实例各自一份</b>的。实例 A 更新数据后清了 Redis，实例 B 的 Caffeine 里
 * 仍然留着旧值。因此 {@link #evict} <b>必须</b>在清完 L2 之后向
 * {@link CacheKeys#CHANNEL_INVALIDATE} 发一条广播，让所有实例清掉自己的 L1。
 * 只靠把 L1 的 TTL 设短只能缩小不一致窗口，无法消除。
 *
 * <h3>三个经典问题在这里的落点</h3>
 * <ul>
 *   <li><b>穿透</b>：回源结果为 null 时写入带 {@code __null__} 后缀的空值占位，
 *       TTL 显著短于正常值（默认 30s）。这样既挡住了对不存在数据的反复查询，
 *       又不会让「刚录入的数据搜不到」持续太久。</li>
 *   <li><b>击穿</b>：热点 key 过期的瞬间，用 Redis 的 SET NX 做互斥，
 *       只放一个请求回源，其余短暂自旋后重读缓存。</li>
 *   <li><b>雪崩</b>：写入时在 TTL 上叠加 ±10% 的随机抖动，
 *       避免同一批 key 在同一秒集体过期。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwoLevelCacheService implements CacheService, MessageListener {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final KumaSearchProperties properties;
    private final RedisMessageListenerContainer listenerContainer;

    /** L1 本地缓存。key 与 L2 完全一致，便于广播失效时直接对应。 */
    private volatile Cache<String, Object> localCache;

    /** 本实例的唯一标识，用于在广播时跳过自己（自己已经在 evict 里清过了）。 */
    private final String instanceId = java.util.UUID.randomUUID().toString();

    /** 每次 SCAN 拉取的 key 数量。取 500 是「往返次数」与「单次阻塞时长」的折中。 */
    private static final long SCAN_BATCH = 500;

    /**
     * 比较并删除的 Lua 脚本：只有值等于传入的 token 时才删除。
     *
     * <p>必须放在服务端原子执行。写成「先 GET 比较、再 DEL」两步的话，
     * 两步之间锁可能刚好过期并被别人持有，于是仍然会删掉别人的锁。
     */
    private static final org.springframework.data.redis.core.script.RedisScript<Long> RELEASE_LOCK_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    @jakarta.annotation.PostConstruct
    public void init() {
        if (properties.getCache().isLocalEnabled()) {
            this.localCache = Caffeine.newBuilder()
                    .maximumSize(properties.getCache().getLocalMaxSize())
                    .expireAfterWrite(Duration.ofSeconds(properties.getCache().getLocalTtlSeconds()))
                    .recordStats()
                    .build();
        }
        // 订阅失效广播频道。这一步是二级缓存正确性的关键，不是可选项。
        listenerContainer.addMessageListener(this, new ChannelTopic(CacheKeys.CHANNEL_INVALIDATE));
        log.info("二级缓存初始化完成，L1 启用={}，实例标识={}",
                properties.getCache().isLocalEnabled(), instanceId);
    }

    // ==================================================================================
    //  读
    // ==================================================================================

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, long ttlSeconds, Class<T> type, Supplier<T> loader) {
        // L1
        if (localCache != null) {
            Object local = localCache.getIfPresent(key);
            if (local != null) {
                return unwrapNull(local, type);
            }
        }

        // L2
        Object cached = safeRedisGet(key);
        if (cached != null) {
            if (localCache != null) {
                localCache.put(key, cached);
            }
            return unwrapNull(cached, type);
        }

        // 回源，带互斥保护
        T loaded = loadWithMutex(key, ttlSeconds, loader);

        if (localCache != null) {
            localCache.put(key, (Object) loaded);
        }
        return loaded;
    }

    /**
     * 带互斥的回源。只有抢到锁的请求去查下游，其余请求短暂等待后重读 L2。
     *
     * <p>等待时间刻意设得很短（默认 500ms）：如果回源本身就要 1 秒，
     * 让 200 个请求都排队等 1 秒，比让它们各自去查一次 ES 更糟 ——
     * 后者的总耗时反而更低，且天然形成了对下游的限流。所以这里的锁是「削峰」而不是「串行化」。
     */
    private <T> T loadWithMutex(String key, long ttlSeconds, Supplier<T> loader) {
        String lockKey = CacheKeys.rebuildLock(key);
        String token = tryLock(lockKey);

        if (token == null) {
            // 没抢到锁：等一下再读一次 L2，读不到就自己回源（宁可多打一次下游，也不让请求排队）
            sleepQuietly(properties.getCache().getLockWaitMillis());
            Object retry = safeRedisGet(key);
            if (retry != null) {
                return unwrapNull(retry, null);
            }
            return loader.get();
        }

        try {
            // 双重检查：等锁期间可能已有别的请求把值写进去了
            Object doubleCheck = safeRedisGet(key);
            if (doubleCheck != null) {
                return unwrapNull(doubleCheck, null);
            }

            T value = loader.get();
            writeWithJitter(key, value, ttlSeconds);
            return value;
        } finally {
            releaseLock(lockKey, token);
        }
    }

    /**
     * 释放互斥锁，仅当锁仍属于本请求时才删除。
     *
     * <p>锁的 TTL 只有 {@code lock-wait-millis × 2}（默认 1 秒），而回源的 ES 查询在压力大时
     * 完全可能超过 1 秒。一旦超时，锁会被自动释放并可能被<b>另一个</b>请求获取；
     * 此时若本请求在 finally 里直接 {@code DEL}，删掉的就是别人的锁 ——
     * 第三个请求随即也能拿到锁，互斥保护形同虚设。
     *
     * <p>用 Lua 把「比较 token」与「删除」合成一个原子操作。分两步做（先 GET 再 DEL）
     * 之间仍有竞态窗口，等于没解决问题。
     */
    private void releaseLock(String lockKey, String token) {
        try {
            stringRedisTemplate.execute(RELEASE_LOCK_SCRIPT,
                    java.util.Collections.singletonList(lockKey), token);
        } catch (Exception e) {
            // 释放失败只等 TTL 自然过期，不影响业务：最多是这段时间内该 key 少了互斥保护。
            log.warn("释放缓存互斥锁失败，key={}，原因={}", lockKey, e.getMessage());
        }
    }

    // ==================================================================================
    //  写 / 失效
    // ==================================================================================

    @Override
    public void put(String key, Object value, long ttlSeconds) {
        writeWithJitter(key, value, ttlSeconds);
        if (localCache != null && value != null) {
            localCache.put(key, value);
        }
    }

    @Override
    public void evict(String key) {
        // 先清 L2，再广播 —— 顺序反了的话，广播到达的瞬间 L2 里还是旧值，
        // 别的实例回填 L1 会把旧值又拉回来。
        safeRedisDelete(key);
        if (localCache != null) {
            localCache.invalidate(key);
        }
        // 广播让其它实例清掉各自的 L1。跳过自己的逻辑在 onMessage 里处理。
        try {
            stringRedisTemplate.convertAndSend(CacheKeys.CHANNEL_INVALIDATE, instanceId + "|" + key);
        } catch (Exception e) {
            // 广播失败不能影响主流程：最多是别的实例多脏一会儿，L1 的短 TTL 会兜底。
            log.warn("缓存失效广播失败，key={}，原因={}", key, e.getMessage());
        }
    }

    @Override
    public void evictByPrefix(String prefix) {
        // L1：进程内，key 数量受 local-max-size 约束，直接遍历即可。
        // 先 toList() 落成快照再删 —— 边遍历 asMap().keySet() 边 invalidate 是未定义行为。
        if (localCache != null) {
            localCache.asMap().keySet().stream()
                    .filter(k -> k.startsWith(prefix))
                    .toList()
                    .forEach(localCache::invalidate);
        }

        // L2：必须用 SCAN 逐批拉取。
        //
        // 【曾经的真实缺陷】这段代码的注释写着「用 SCAN 而不是 KEYS」，实现却在调
        // stringRedisTemplate.keys() —— 那正是 KEYS 命令。KEYS 会一次性遍历整个 key 空间
        // 并在执行期间阻塞 Redis 的**单线程**事件循环，key 数以万计时足以让所有请求超时。
        // 而调用这个方法的是索引重建，它要清掉的 key 恰恰是上万条。
        //
        // 顺带一提，原来的"分批删除"也没有生效：groupingBy(k -> keys.size() / 100)
        // 的分组键是个**与 k 无关的常量**，全部 key 会落进同一个桶，等于一次 DEL 全部。
        // 真正的分批必须是「边 SCAN 边攒够一批就删」。
        long deleted = 0;
        try (org.springframework.data.redis.core.Cursor<String> cursor = stringRedisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(prefix + "*")
                        .count(SCAN_BATCH)
                        .build())) {

            List<String> batch = new ArrayList<>((int) SCAN_BATCH);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= SCAN_BATCH) {
                    stringRedisTemplate.delete(batch);
                    deleted += batch.size();
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                stringRedisTemplate.delete(batch);
                deleted += batch.size();
            }
            if (deleted > 0) {
                log.info("按前缀清理缓存完成，prefix={}，清理数量={}", prefix, deleted);
            }
        } catch (Exception e) {
            // SCAN 中途失败时已删除的部分不会回滚 —— 那正是我们想要的方向（多删总比留脏数据好）。
            log.warn("按前缀清理缓存失败，prefix={}，已清理={}，原因={}", prefix, deleted, e.getMessage());
        }

        try {
            stringRedisTemplate.convertAndSend(CacheKeys.CHANNEL_INVALIDATE, instanceId + "|PREFIX|" + prefix);
        } catch (Exception e) {
            log.warn("前缀失效广播失败，prefix={}，原因={}", prefix, e.getMessage());
        }
    }

    @Override
    public void evictLocal(String key) {
        if (localCache == null) {
            return;
        }
        if (key.startsWith("PREFIX|")) {
            String prefix = key.substring("PREFIX|".length());
            localCache.asMap().keySet().stream()
                    .filter(k -> k.startsWith(prefix))
                    .forEach(localCache::invalidate);
        } else {
            localCache.invalidate(key);
        }
    }

    /** 接收其它实例的失效广播。 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        int sep = body.indexOf('|');
        if (sep <= 0) {
            return;
        }
        String sender = body.substring(0, sep);
        // 自己发的跳过：evict 里已经清过，重复清一次无害但会白白遍历 L1。
        if (instanceId.equals(sender)) {
            return;
        }
        evictLocal(body.substring(sep + 1));
        log.debug("收到缓存失效广播，来源={}，内容={}", sender, body.substring(sep + 1));
    }

    // ==================================================================================
    //  内部工具
    // ==================================================================================

    /**
     * 写入 L2，并对 TTL 加随机抖动。
     *
     * <p>抖动范围 ±10%。这是防止雪崩的最低成本手段：如果不加，
     * 一批在索引重建后同时写入的缓存会在同一秒集体过期，瞬时把全部请求压到 ES 上。
     */
    private void writeWithJitter(String key, Object value, long ttlSeconds) {
        // 调用方没给 TTL（<=0）时退回全局默认值，而不是让它变成一个 1 秒的缓存。
        // 这里用上 kuma.cache.redis-ttl-seconds，使这个配置项真正有作用 ——
        // 先前它被定义在配置类与 application.yml 里，而代码从不读取，
        // 运维按文档调整它不会有任何效果，属于"看起来能调的假旋钮"。
        long effectiveTtl = ttlSeconds > 0 ? ttlSeconds : properties.getCache().getRedisTtlSeconds();
        long jitterRange = Math.max(1, effectiveTtl / 10);
        long jitter = ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1);
        long finalTtl = Math.max(1, effectiveTtl + jitter);

        try {
            if (value == null) {
                // 空值占位：用固定的短 TTL，不参与抖动 —— 它的作用只是挡住重复查询。
                redisTemplate.opsForValue().set(
                        CacheKeys.nullPlaceholder(key),
                        NullHolder.INSTANCE,
                        properties.getCache().getNullTtlSeconds(),
                        TimeUnit.SECONDS);
            } else {
                redisTemplate.opsForValue().set(key, value, finalTtl, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            // 缓存写失败不应让业务失败：降级为「本次不走缓存」。
            log.warn("写入缓存失败，key={}，原因={}", key, e.getMessage());
        }
    }

    /**
     * 处理空值占位。
     *
     * <p>占位对象在 L2 里是 {@link NullHolder}，但走完序列化反序列化后
     * 可能变成 Map 之类的结构，所以这里同时判断 L2 里有没有 key，
     * 而不是只依赖对象类型 —— 这是踩过坑之后的做法。
     */
    @SuppressWarnings("unchecked")
    private <T> T unwrapNull(Object value, Class<T> type) {
        if (value == null || value instanceof NullHolder) {
            return null;
        }
        if (value instanceof java.util.Map<?, ?> map
                && map.size() == 1
                && map.containsKey("@class")
                && String.valueOf(map.get("@class")).contains("NullHolder")) {
            return null;
        }
        return (T) value;
    }

    private Object safeRedisGet(String key) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                return value;
            }
            // 检查空值占位：命中则说明下游确实没有这条数据，直接返回 null 而不是回源。
            return redisTemplate.opsForValue().get(CacheKeys.nullPlaceholder(key));
        } catch (Exception e) {
            log.warn("读取缓存失败，key={}，降级为回源，原因={}", key, e.getMessage());
            return null;
        }
    }

    private void safeRedisDelete(String key) {
        try {
            stringRedisTemplate.delete(key);
            stringRedisTemplate.delete(CacheKeys.nullPlaceholder(key));
        } catch (Exception e) {
            log.warn("删除缓存失败，key={}，原因={}", key, e.getMessage());
        }
    }

    /**
     * 尝试获取回源互斥锁。
     *
     * @return 成功时返回本次持有的唯一 token（释放时用它校验归属）；
     *         Redis 异常时返回一个 token 并<b>放行</b> —— 缓存层故障不应该把整条检索链路挂死，
     *         此时退化为"无互斥保护地直接回源"，是刻意选择的降级方向。
     *         抢锁失败（锁被别人持有）返回 {@code null}。
     */
    private String tryLock(String lockKey) {
        String token = instanceId + ":" + java.util.UUID.randomUUID();
        try {
            Boolean ok = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, token,
                            properties.getCache().getLockWaitMillis() * 2, TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(ok) ? token : null;
        } catch (Exception e) {
            log.warn("获取缓存重建锁失败，降级为直接回源，原因={}", e.getMessage());
            return token;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(10, millis / 5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 空值占位标记。单独定义类型而不是用字符串，避免与真实的空字符串缓存值混淆。 */
    public static final class NullHolder implements java.io.Serializable {
        public static final NullHolder INSTANCE = new NullHolder();

        private NullHolder() {
        }
    }
}
