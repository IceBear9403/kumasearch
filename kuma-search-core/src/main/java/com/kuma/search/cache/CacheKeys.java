package com.kuma.search.cache;

/**
 * 缓存 key 的唯一构造入口。
 *
 * <p>把所有 key 拼接收在一个类里，是为了避免「某个 service 里手写了一个少了下划线前缀的 key」
 * 导致的缓存永不命中 —— 这类 bug 不会报错，只会静默地让缓存失效，非常难发现。
 *
 * <p>统一前缀 {@code kuma:}，方便运维按前缀做容量统计与批量清理（{@code SCAN MATCH kuma:search:*}）。
 *
 * <h3>最关键的一条规则</h3>
 * {@link #search} 与 {@link #suggest} 的 key 里<b>必须</b>包含索引版本号。
 * 因为 reindex 之后，同一个查询在语义上已经指向了新的文档集合；如果 key 不变，
 * 缓存会把旧索引的结果继续返回最长一整个 TTL —— 表现为「重建索引了但搜出来的还是旧的」。
 * 版本号来自 {@link #indexTypeConfig} 缓存中的配置（{@code IndexDefinition.version}），
 * 由 {@code DynamicIndexManager} 在重建时递增。
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    /** 全局前缀。 */
    public static final String PREFIX = "kuma:";

    /** 缓存失效广播的频道名，供 {@code TwoLevelCacheService} 通知其它实例清理 L1。 */
    public static final String CHANNEL_INVALIDATE = PREFIX + "cache:invalidate";

    // ===== 索引相关 =====

    /**
     * 索引类型配置的缓存（变更频率极低，TTL 可以给长一些）。
     *
     * <p>索引版本号就从这份配置里取（{@code IndexDefinition.version}），
     * <b>不再单独用一个 Redis key 存</b>：那样会形成两个真源，
     * 重建索引时若只更新了其中一个，缓存 key 里的版本号就会与实际索引对不上，
     * 表现为「重建完了但搜出来还是旧的」，而且不会有任何报错。
     */
    public static String indexTypeConfig(String typeCode) {
        return PREFIX + "idx:cfg:" + typeCode;
    }

    /**
     * 公开「可检索类型列表」的缓存（搜索页类型选择器的数据来源）。
     *
     * <p>它与 {@link #indexTypeConfig} 是<b>同一张表的两个视图</b>：前者按 typeCode 逐个取，
     * 后者一次性取全部启用项。因此两者的失效必须同进同出 ——
     * 改了类型配置却只失效了其中一个，表现是「后台已经停用了某类型，公开列表里它还挂着」，
     * 而这种不一致只会在界面上被人看见，没有任何报错。失效点收在
     * {@code IndexTypeConfigServiceImpl#invalidate} 一个方法里，就是为了不让这件事靠人记。
     *
     * <p>不带参数的 key：列表本身没有维度，任何参数都会被拼成不同的 key，
     * 从而绕过上面那条"一处失效"的约定。
     */
    public static String indexTypeList() {
        return PREFIX + "idx:types";
    }

    // ===== 检索相关 =====

    /**
     * 搜索结果缓存。
     *
     * @param typeCode    类型编码
     * @param indexVersion 索引版本号，见类注释
     * @param paramHash   检索参数的稳定哈希（由 {@code SearchServiceImpl} 生成，
     *                    必须把影响结果的所有维度都纳入，且<b>排除</b> userId 这类不影响结果的字段）
     */
    public static String search(String typeCode, String indexVersion, String paramHash) {
        return PREFIX + "search:" + typeCode + ":v" + indexVersion + ":" + paramHash;
    }

    /** 搜索建议缓存。 */
    public static String suggest(String typeCode, String indexVersion, String prefix) {
        return PREFIX + "suggest:" + typeCode + ":v" + indexVersion + ":" + prefix;
    }

    /** 热词榜（ZSET）。按天分桶，便于做「今日热词」与「近 7 天热词」的滑动统计。 */
    public static String hotWords(String typeCode, String yyyyMMdd) {
        return PREFIX + "hot:" + typeCode + ":" + yyyyMMdd;
    }

    /** 用户个人搜索历史（ZSET，score 为时间戳，便于按时间倒序且去重）。 */
    public static String userHistory(Long userId, String typeCode) {
        return PREFIX + "hist:" + userId + ":" + typeCode;
    }

    /** 空值占位 key，用于防止缓存穿透。 */
    public static String nullPlaceholder(String rawKey) {
        return rawKey + ":__null__";
    }

    /**
     * 热词去重标记，key 形如 {@code kuma:hot:dedup:{typeCode}:{ip}:{keywordHash}}。
     *
     * <p>搜索改成匿名可用之后，热词是 {@code ZINCRBY} 累加的，于是
     * <b>任何一个人都能靠反复搜同一个词把指定词条刷上热词榜</b>。
     * 这个 key 让「同一 IP + 同一关键词」在窗口内只计一次，
     * 把刷榜的成本从「同一个 IP 搜 30 次」抬高到「需要多个 IP」。
     *
     * <p>⚠️ <b>它只抬高成本，挡不住分布式刷量。</b>有几十个 IP 就能破，
     * 而且拦不住「用几十个不同的垃圾词互相助攻」这种打法。
     * 热词榜<b>仍必须有人工巡检</b>，不要把这里当成「已经防住了」。
     *
     * <p>放在 {@code kuma:hot:} 之后而不是限流的 {@code kuma:rate:} 前缀下：
     * 它约束的是<b>热词数据的写入</b>，与它相邻的是热词榜 ZSET，
     * 清理与观测都应当与热词一起做。限流前缀下的是「请求频率」，
     * 两者的排查场景不同。
     *
     * <p>关键词只存散列：这个 key 本可以只承担「判断是否重复」一个职责，
     * 没必要在 Redis 里再留一份用户真实搜索词的清单。
     */
    public static String hotDedup(String typeCode, String clientIp, String keywordHash) {
        return PREFIX + "hot:dedup:" + typeCode + ":" + clientIp + ":" + keywordHash;
    }

    // ===== 并发控制 =====

    /** 缓存重建互斥锁，防止热点 key 失效瞬间大量请求同时打到 ES（缓存击穿）。 */
    public static String rebuildLock(String rawKey) {
        return PREFIX + "lock:cache:" + rawKey.hashCode();
    }

    /**
     * 索引重建的分布式锁。
     * 全量重建是重操作，多实例部署时必须保证同一类型同时只有一个重建任务在跑。
     */
    public static String reindexLock(String typeCode) {
        return PREFIX + "lock:reindex:" + typeCode;
    }

    /** 定时同步任务的分布式锁，保证多实例下同一任务不被重复执行。 */
    public static String syncTaskLock(String taskName) {
        return PREFIX + "lock:sync:" + taskName;
    }
}
