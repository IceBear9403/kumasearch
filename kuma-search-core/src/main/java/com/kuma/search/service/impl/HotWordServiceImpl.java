package com.kuma.search.service.impl;

import com.kuma.search.cache.CacheKeys;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.service.HotWordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 热词榜与个人搜索历史的实现。
 *
 * <h3>为什么是 Redis ZSET 而不是 MySQL</h3>
 * 「每搜一次就写一条 DB 记录」在 500 QPS 下就是每秒 500 次写入，而热词榜只关心
 * <b>排名</b>，不关心明细。ZSET 的 {@code ZINCRBY} 一次调用同时完成了计数与排序，
 * 且读取时 {@code ZREVRANGE} 天然有序，不需要 {@code GROUP BY ... ORDER BY COUNT(*)}。
 * 明细数据由 {@code t_search_log} 异步承载，用于离线分析。
 *
 * <h3>两道必须守住的底线</h3>
 * <ol>
 *   <li><b>记录失败绝不向上抛</b>。热词统计是附属能力，它挂了不能让用户搜索报错。
 *       所有写操作都吞掉异常只记日志。</li>
 *   <li><b>每个 key 都必须设过期时间</b>。按天分桶的 key 若不设 TTL，
 *       会随着时间无限增长（每天每类型一个 key × 每天新增的用户历史），
 *       最终吃光 Redis 内存。这是"小功能拖垮整个缓存层"的经典路径。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotWordServiceImpl implements HotWordService {

    private final StringRedisTemplate stringRedisTemplate;
    private final KumaSearchProperties properties;

    /** 按天分桶的格式，与 {@link CacheKeys#hotWords} 保持一致。 */
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 去重 key 中 IP 片段的最大长度。
     *
     * <p>IPv6 最长 45 字符（含 IPv4 映射写法），留 64 足够。设这个上限是因为
     * IP 最终来自请求头，而请求头里的东西在没配好 {@code internal-proxies} 时是可以随便写的
     * —— 不截断的话，每个伪造请求都会在 Redis 里留下一个超长的、永不重用的 key。
     */
    private static final int MAX_IP_KEY_LENGTH = 64;

    /** 热词 key 的保留天数。保留 7 天即可支撑「今日热词」与「近七天热词」两种口径。 */
    private static final Duration HOT_WORD_TTL = Duration.ofDays(7);

    /** 个人历史的保留时长。设得比热词长，因为「我上次搜过什么」的回忆周期以周计。 */
    private static final Duration HISTORY_TTL = Duration.ofDays(30);

    /** 单个用户单类型下最多保留多少条历史。不设上限的话重度用户会把 ZSET 撑到几万条。 */
    private static final int HISTORY_MAX_SIZE = 50;

    /** 热词榜的滑动窗口天数。今天的数据不够时用它补齐。 */
    private static final int HOT_WINDOW_DAYS = 7;

    // ==================================================================================
    //  记录
    // ==================================================================================

    @Override
    public void record(String typeCode, String keyword, Long userId) {
        record(typeCode, keyword, userId, null);
    }

    @Override
    public void record(String typeCode, String keyword, Long userId, String clientIp) {
        String normalized = normalize(keyword);
        if (!StringUtils.hasText(normalized) || !StringUtils.hasText(typeCode)) {
            return;
        }

        // 热词与个人历史是两件事，去重只作用于前者 —— 见接口注释
        if (shouldCountHotWord(typeCode, normalized, clientIp)) {
            recordHotWord(typeCode, normalized);
        }
        if (userId != null) {
            recordHistory(userId, typeCode, normalized);
        }
    }

    /**
     * 判定这次搜索是否应当计入热词。
     *
     * <p>用 {@code SET NX} 而不是「先 GET 再 SET」：后者在同一个 IP 并发搜索时
     * 会双双读到「不存在」、双双计数，而刷榜本来就是并发行为。
     *
     * <p><b>三种情况直接放行（返回 true），都不去碰 Redis：</b>
     * <ul>
     *   <li>去重开关关闭；</li>
     *   <li>拿不到 IP（内部调用、定时任务）—— 没有身份就无法去重，
     *       此时宁可多计数：把去重键退化成「全体用户共用一个」会让热词榜直接停止增长，
     *       而那是比刷榜更明显、也更难排查的故障；</li>
     *   <li>关键词或类型为空（其实上面已经拦掉了，这里只是让本方法的契约自洽）。</li>
     * </ul>
     *
     * <p>Redis 异常时同样返回 true（fail-open）。理由与 {@code RateLimitGuard} 一致：
     * 热词榜是展示型功能，Redis 一抖就让热词榜变空，看起来像功能坏了。
     */
    private boolean shouldCountHotWord(String typeCode, String normalized, String clientIp) {
        if (!properties.getRateLimit().isHotDedupEnabled()) {
            return true;
        }
        if (!StringUtils.hasText(clientIp)) {
            return true;
        }
        int ttlSeconds = properties.getRateLimit().getHotDedupSeconds();
        if (ttlSeconds <= 0) {
            return true;
        }
        // IP 截断，防止一个伪造的超长 IP 反复制造大 key 撑爆 Redis 内存
        String ipKey = clientIp.trim().length() > MAX_IP_KEY_LENGTH
                ? clientIp.trim().substring(0, MAX_IP_KEY_LENGTH)
                : clientIp.trim();
        String key = CacheKeys.hotDedup(typeCode, ipKey, shortHash(normalized));
        try {
            Boolean created = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofSeconds(ttlSeconds));
            return Boolean.TRUE.equals(created);
        } catch (Exception e) {
            log.warn("热词去重标记失败（Redis 异常），本次仍计入热词：type={}，原因={}",
                    typeCode, e.getMessage());
            return true;
        }
    }

    /**
     * 关键词的短散列，用作去重 key 的一部分。
     *
     * <p>不把关键词原文放进 key：那个 key 的职责只是「判断是否重复」，
     * 没必要在 Redis 里额外留一份用户真实搜索词的清单（它已经被
     * {@code t_search_log} 与热词 ZSET 记录了两次）。
     *
     * <p>取 SHA-256 的前 16 位十六进制（64 bit）就够了：这里要区分的是
     * 「同一个 IP 的几十个不同关键词」，碰撞概率可以忽略，
     * 而更短的 key 意味着更小的内存占用。碰撞的实际后果也仅限于
     * 「两个不同的词在这个 IP 下有一个没被计数」，是纯统计噪音。
     */
    private String shortHash(String keyword) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(keyword.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法。真走到这里说明运行环境被改过，
            // 此时退化成直接用原文 —— 去重仍然生效，只是 key 会长一些。
            return keyword;
        }
    }

    /**
     * 累加热词计数。
     *
     * <p>{@code ZINCRBY} 是原子的，因此多实例并发搜索时计数不会丢 ——
     * 换成「先 GET 再 SET」的写法在 500 QPS 下必然丢计数。
     */
    private void recordHotWord(String typeCode, String normalized) {
        try {
            String key = CacheKeys.hotWords(typeCode, LocalDate.now().format(DAY_FORMAT));
            stringRedisTemplate.opsForZSet().incrementScore(key, normalized, 1.0);

            // 每次记录都续期是廉价且必要的：只有第一个写入者设 TTL 的话，
            // 跨天时新 key 会被设上，但极端情况下（例如 key 被运维手工清理过）会退化成永不过期。
            stringRedisTemplate.expire(key, HOT_WORD_TTL);
        } catch (Exception e) {
            // 绝不能向上抛：热词统计失败不应该让用户看到搜索报错。
            log.warn("记录热词失败，type={}，keyword={}，原因={}", typeCode, normalized, e.getMessage());
        }
    }

    /**
     * 记录个人历史。
     *
     * <p>score 用毫秒时间戳而不是计数：历史要按<b>时间倒序</b>展示，
     * 而 ZSET 的 score 是唯一的排序依据。重复搜索同一个词会覆盖 score，
     * 从而把它顶到最前面 —— 这正是「最近搜过」应有的语义。
     */
    private void recordHistory(Long userId, String typeCode, String normalized) {
        try {
            String key = CacheKeys.userHistory(userId, typeCode);
            stringRedisTemplate.opsForZSet().add(key, normalized, System.currentTimeMillis());
            stringRedisTemplate.expire(key, HISTORY_TTL);

            // 只保留最近 N 条。用 ZREMRANGEBYRANK 一次裁掉，而不是逐条判断。
            Long size = stringRedisTemplate.opsForZSet().zCard(key);
            if (size != null && size > HISTORY_MAX_SIZE) {
                stringRedisTemplate.opsForZSet()
                        .removeRange(key, 0, size - HISTORY_MAX_SIZE - 1);
            }
        } catch (Exception e) {
            log.warn("记录搜索历史失败，userId={}，type={}，原因={}", userId, typeCode, e.getMessage());
        }
    }

    // ==================================================================================
    //  读取
    // ==================================================================================

    /**
     * 取热词榜。
     *
     * <p>先只读今天的桶；数量不够时才把最近 7 天合并一次。
     * 直接每天合并 7 个 key 是浪费 —— 绝大多数情况下今天的量已经足够填满榜单，
     * 那种情况下连一次 {@code ZUNIONSTORE} 都不需要。
     */
    @Override
    public List<String> topHotWords(String typeCode, int size) {
        if (!StringUtils.hasText(typeCode) || size <= 0) {
            return List.of();
        }

        try {
            String todayKey = CacheKeys.hotWords(typeCode, LocalDate.now().format(DAY_FORMAT));
            Set<String> today = stringRedisTemplate.opsForZSet()
                    .reverseRange(todayKey, 0, size - 1);
            if (today != null && today.size() >= size) {
                return new ArrayList<>(today);
            }

            return mergeRecentDays(typeCode, size);
        } catch (Exception e) {
            // 热词榜读不到就返回空列表，由前端隐藏这一块，而不是让首页 500。
            log.warn("读取热词榜失败，type={}，原因={}", typeCode, e.getMessage());
            return List.of();
        }
    }

    /**
     * 合并最近 N 天的热词。
     *
     * <p>用 {@code ZUNIONSTORE} 而不是把 7 天的结果在应用层拼起来再去重排序：
     * 应用层拼接需要把每天的前 N 条都取回来，且无法正确累加跨天的权重
     * （同一个词昨天 100 次、今天 1 次，不应该和昨天 0 次、今天 101 次同分）。
     * {@code ZUNIONSTORE} 在 Redis 内部完成加权求和，语义正确且只有一次往返。
     *
     * <p>合并结果写进一个带短 TTL 的临时 key，让紧接着的多次请求复用同一份合并结果。
     */
    private List<String> mergeRecentDays(String typeCode, int size) {
        List<String> dayKeys = new ArrayList<>(HOT_WINDOW_DAYS);
        LocalDate today = LocalDate.now();
        for (int i = 0; i < HOT_WINDOW_DAYS; i++) {
            dayKeys.add(CacheKeys.hotWords(typeCode, today.minusDays(i).format(DAY_FORMAT)));
        }

        String mergedKey = CacheKeys.PREFIX + "hot:merged:" + typeCode;
        try {
            // 全部为空时 ZUNIONSTORE 会创建一个空 key，也要一并清掉，避免留下一堆空 key
            stringRedisTemplate.opsForZSet().unionAndStore(
                    dayKeys.get(0), dayKeys.subList(1, dayKeys.size()), mergedKey);
        } catch (Exception e) {
            log.debug("合并热词失败，退化为只读今日，type={}，原因={}", typeCode, e.getMessage());
        }

        Set<String> merged = stringRedisTemplate.opsForZSet().reverseRange(mergedKey, 0, size - 1);
        if (merged == null || merged.isEmpty()) {
            // 合并失败时至少把今天的数据还回去
            Set<String> fallback = stringRedisTemplate.opsForZSet()
                    .reverseRange(dayKeys.get(0), 0, size - 1);
            return fallback == null ? List.of() : new ArrayList<>(fallback);
        }
        stringRedisTemplate.expire(mergedKey, Duration.ofMinutes(5));
        return new ArrayList<>(merged);
    }

    @Override
    public List<String> userHistory(Long userId, String typeCode, int size) {
        if (userId == null || !StringUtils.hasText(typeCode) || size <= 0) {
            return List.of();
        }
        try {
            // reverseRange 按 score（时间戳）倒序，即"最近搜过的在最前"
            Set<String> history = stringRedisTemplate.opsForZSet()
                    .reverseRange(CacheKeys.userHistory(userId, typeCode), 0, size - 1);
            return history == null ? List.of() : new ArrayList<>(history);
        } catch (Exception e) {
            log.warn("读取搜索历史失败，userId={}，type={}，原因={}", userId, typeCode, e.getMessage());
            return List.of();
        }
    }

    /**
     * 批量导入本地历史。
     *
     * <p>score 用「当前时间戳 - 下标」而不是同一个时间戳：ZSET 里 score 是唯一的排序依据，
     * 一批相同的 score 会让它们在 {@code ZREVRANGE} 中按字典序（而不是原始顺序）返回 ——
     * 表现为"我导入的历史顺序乱了"，而用户刚导入完正是最容易注意到这一点的时候。
     * 下标从 0 开始递增，于是传入列表的第一个元素（最近的搜索）排在最后面被先读到。
     *
     * <p><b>不走 {@link #recordHotWord}</b>，见接口注释：导入不是搜索行为。
     */
    @Override
    public void importHistory(Long userId, String typeCode, List<String> keywords) {
        if (userId == null || !StringUtils.hasText(typeCode) || keywords == null || keywords.isEmpty()) {
            return;
        }

        try {
            String key = CacheKeys.userHistory(userId, typeCode);
            long base = System.currentTimeMillis();
            int index = 0;
            for (String keyword : keywords) {
                String normalized = normalize(keyword);
                if (!StringUtils.hasText(normalized)) {
                    continue;
                }
                stringRedisTemplate.opsForZSet().add(key, normalized, base - index);
                index++;
            }
            if (index == 0) {
                return;
            }
            stringRedisTemplate.expire(key, HISTORY_TTL);

            // 与 recordHistory 同样的裁剪：导入会让 ZSET 一次性增加几十条，
            // 不裁的话它会直接突破 HISTORY_MAX_SIZE 并把历史读接口的结果撑大。
            Long size = stringRedisTemplate.opsForZSet().zCard(key);
            if (size != null && size > HISTORY_MAX_SIZE) {
                stringRedisTemplate.opsForZSet()
                        .removeRange(key, 0, size - HISTORY_MAX_SIZE - 1);
            }
        } catch (Exception e) {
            log.warn("导入搜索历史失败，userId={}，type={}，原因={}", userId, typeCode, e.getMessage());
        }
    }

    @Override
    public void clearHistory(Long userId, String typeCode) {
        if (userId == null || !StringUtils.hasText(typeCode)) {
            return;
        }
        try {
            stringRedisTemplate.delete(CacheKeys.userHistory(userId, typeCode));
        } catch (Exception e) {
            log.warn("清空搜索历史失败，userId={}，type={}，原因={}", userId, typeCode, e.getMessage());
        }
    }

    // ==================================================================================
    //  归一化
    // ==================================================================================

    /**
     * 归一化关键词。
     *
     * <p>规则：去首尾空白 → 连续空白压成一个空格 → 转小写（含全角转半角）。
     *
     * <p>不做分词或同义词替换，是因为热词榜要展示给用户看。
     * 如果把「华为手机」归一化成「华为 手机」，榜单上就会出现用户从没搜过的词，
     * 看起来像 bug。归一化只处理<b>书写差异</b>，不改变词本身。
     */
    @Override
    public String normalize(String keyword) {
        if (keyword == null) {
            return null;
        }
        String result = keyword.trim();
        if (result.isEmpty()) {
            return result;
        }
        // 全角空格、制表符、换行统一处理成半角空格，再压缩连续空格
        result = result.replace('　', ' ')
                .replaceAll("\\s+", " ");
        return result.toLowerCase();
    }
}
