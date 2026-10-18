package com.kuma.search.service.impl;

import com.kuma.search.cache.CacheKeys;
import com.kuma.search.cache.CacheService;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.dto.resp.SuggestDTO;
import com.kuma.search.es.gateway.EsSearchGateway;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.service.HotWordService;
import com.kuma.search.service.IndexTypeConfigService;
import com.kuma.search.service.SuggestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索建议的实现：把三条来源合并成一个有序、去重的列表。
 *
 * <h3>合并顺序是有讲究的，不是随便排的</h3>
 * <pre>
 *   个人历史  >  热词榜  >  索引建议
 * </pre>
 * 用户刚搜过的词，在这次输入时最可能是同一个意图。如果把它排在热门词后面，
 * 会出现"我明明刚搜过这个，输入框里却找不到"的别扭体验。
 * 索引建议排在最后，是因为它来自文档标题，五花八门、长度不可控，
 * 更适合在用户已经输入得比较具体时兜底。
 *
 * <h3>缓存边界：什么能共享，什么绝对不能</h3>
 * 个人历史是<b>按用户隔离</b>的数据，绝不能放进任何跨用户共享的缓存 key，
 * 那会把 A 用户的搜索历史展示给 B 用户 —— 既是严重的隐私事故，也是数据泄漏。
 * 因此只有"索引建议"这一部分走共享缓存，历史部分每次直接从该用户的 Redis ZSET 读。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestServiceImpl implements SuggestService {

    private final IndexTypeConfigService indexTypeConfigService;
    private final EsSearchGateway esSearchGateway;
    private final HotWordService hotWordService;
    private final CacheService cacheService;
    private final KumaSearchProperties properties;

    /** 默认返回条数。 */
    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 50;

    /** 参与前缀过滤的热词候选池大小。取大一点，否则前缀过滤后可能一条都不剩。 */
    private static final int HOT_CANDIDATE_POOL = 100;

    @Override
    public List<SuggestDTO> suggest(String typeCode, String prefix, Integer size, Long userId) {
        String normalizedPrefix = hotWordService.normalize(prefix);
        int limit = normalizeSize(size);

        IndexDefinition def = indexTypeConfigService.resolveDefinition(typeCode);

        // 用 LinkedHashMap 做去重同时保留插入顺序：
        // 用 HashSet 会丢掉优先级顺序，用 List.contains 则是 O(n²)。
        Map<String, SuggestDTO> merged = new LinkedHashMap<>();

        // 1. 个人历史（最高优先级，且绝不能缓存）
        if (userId != null && StringUtils.hasText(normalizedPrefix)) {
            List<String> history = hotWordService.userHistory(userId, typeCode, limit);
            for (String word : history) {
                if (matchesPrefix(word, normalizedPrefix)) {
                    putIfAbsent(merged, word, "history", null);
                }
            }
        }

        // 2. 热词榜
        if (StringUtils.hasText(normalizedPrefix)) {
            List<String> hotWords = hotWordService.topHotWords(typeCode, HOT_CANDIDATE_POOL);
            for (String word : hotWords) {
                if (matchesPrefix(word, normalizedPrefix)) {
                    putIfAbsent(merged, word, "hot", null);
                }
            }
        }

        // 3. 索引建议（来自 completion suggester，走共享缓存）
        if (StringUtils.hasText(normalizedPrefix) && merged.size() < limit) {
            List<SuggestDTO> indexSuggestions = loadIndexSuggestions(def, normalizedPrefix);
            for (SuggestDTO suggestion : indexSuggestions) {
                putIfAbsent(merged, suggestion.getText(), "index", suggestion.getWeight());
            }
        }

        return trim(merged, limit);
    }

    /**
     * 读取索引建议，带共享缓存。
     *
     * <p>缓存 key 里带索引版本号：重建索引后建议词会变，如果 key 不变，
     * 用户会在最长一个 TTL 内看到已经不存在的旧建议词。
     */
    @SuppressWarnings("unchecked")
    private List<SuggestDTO> loadIndexSuggestions(IndexDefinition def, String prefix) {
        // 索引建议的数量取决于 size，而缓存 key 只按 prefix 划分。
        // 因此固定用 MAX_SIZE 去取并缓存，再由调用方裁剪 ——
        // 否则 size=5 与 size=10 会产生两份缓存，命中率减半且内容重复。
        String cacheKey = CacheKeys.suggest(def.getTypeCode(),
                String.valueOf(def.getVersion()), prefix);

        try {
            List<SuggestDTO> cached = cacheService.get(cacheKey,
                    properties.getCache().getSuggestTtlSeconds(), List.class,
                    () -> esSearchGateway.suggest(def, prefix, MAX_SIZE));
            return cached == null ? List.of() : cached;
        } catch (Exception e) {
            // 缓存层或 ES 出问题时不要影响整个联想功能：降级为直接查 ES（可能也失败，那就是空列表）
            log.warn("索引建议缓存读取失败，降级直查，type={}，prefix={}，原因={}",
                    def.getTypeCode(), prefix, e.getMessage());
            return esSearchGateway.suggest(def, prefix, MAX_SIZE);
        }
    }

    /**
     * 前缀匹配。
     *
     * <p>比的是归一化后的形式，因此「华为 手机」能被「华为手」匹配到 ——
     * 用户输入时不会在意空格。索引建议这一路不需要做这个匹配，
     * 因为 completion suggester 自带前缀语义。
     */
    private boolean matchesPrefix(String word, String normalizedPrefix) {
        if (!StringUtils.hasText(word)) {
            return false;
        }
        String normalized = hotWordService.normalize(word);
        if (normalized == null) {
            return false;
        }
        // 去空格后再比，避免"华为手机"因为中间的空格匹配不上"华为手"
        return normalized.replace(" ", "").startsWith(normalizedPrefix.replace(" ", ""));
    }

    /**
     * 去重写入。
     *
     * <p>去重按<b>归一化后的形式</b>而不是原字符串：热词榜里可能同时存在
     * "华为手机" 与 "华为手机 "（尾部空格的脏数据），按原文去重会让下拉框出现两行看起来一样的选项。
     * 但展示时保留第一次出现的原始写法 —— 归一化只用于判断"是不是同一个词"。
     */
    private void putIfAbsent(Map<String, SuggestDTO> merged, String text, String source, Integer weight) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        String key = hotWordService.normalize(text);
        if (key == null || key.isBlank()) {
            return;
        }
        merged.putIfAbsent(key, SuggestDTO.builder()
                .text(text)
                .source(source)
                .weight(weight == null ? 0 : weight)
                .build());
    }

    private List<SuggestDTO> trim(Map<String, SuggestDTO> merged, int limit) {
        List<SuggestDTO> result = new ArrayList<>(Math.min(limit, merged.size()));
        for (SuggestDTO dto : merged.values()) {
            if (result.size() >= limit) {
                break;
            }
            result.add(dto);
        }
        return result;
    }

    /**
     * 归一化返回条数。
     *
     * <p>上限在这里强制，而不是只依赖 Controller 的校验：
     * 联想接口在最坏情况下要遍历热词候选池 + 查一次 ES，
     * 放过一个 {@code size=10000} 的请求就是一次对集群的定向打击。
     */
    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
