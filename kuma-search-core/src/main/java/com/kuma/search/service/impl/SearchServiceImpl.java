package com.kuma.search.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuma.search.cache.CacheKeys;
import com.kuma.search.cache.CacheService;
import com.kuma.search.common.constant.TraceConstants;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.dto.req.SearchRequestDTO;
import com.kuma.search.dto.resp.FacetDTO;
import com.kuma.search.dto.resp.HitDTO;
import com.kuma.search.dto.resp.SearchResponseDTO;
import com.kuma.search.dto.resp.SuggestDTO;
import com.kuma.search.es.builder.QueryBuilder;
import com.kuma.search.es.gateway.EsSearchGateway;
import com.kuma.search.es.mapping.FieldConfig;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.es.model.EsHit;
import com.kuma.search.es.model.FacetResult;
import com.kuma.search.es.model.SearchParam;
import com.kuma.search.es.model.SearchResult;
import com.kuma.search.repository.entity.SearchLog;
import com.kuma.search.repository.mapper.SearchLogMapper;
import com.kuma.search.service.HotWordService;
import com.kuma.search.service.IndexTypeConfigService;
import com.kuma.search.service.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 检索服务实现：参数校验 → 缓存 → 检索 → 结果加工 → 异步埋点。
 *
 * <h3>缓存 key 的设计（这是本类最容易出错的地方）</h3>
 * key 由「类型 + 索引版本 + 参数指纹」三段构成，缺一不可：
 * <ul>
 *   <li><b>索引版本</b>：重建索引后，同一个查询在语义上指向了新的文档集合。
 *       没有这段，缓存会在整个 TTL 内继续返回旧索引的结果 ——
 *       运维看到的景象是"重建完了，但结果一点没变"。</li>
 *   <li><b>参数指纹</b>：必须覆盖所有影响结果的维度（关键词、字段、过滤、聚合、排序、
 *       翻页、各增强开关），且必须<b>排除</b> userId、clientIp 这类不影响结果、
 *       只影响埋点的字段。把它们算进去会让缓存命中率被用户数除一遍。</li>
 * </ul>
 *
 * <h3>两次查询共享一个缓存条目的陷阱</h3>
 * 参数里的 Map（filters）直接 {@code toString()} 参与指纹计算是危险的：
 * {@code HashMap} 的迭代顺序在不同 JVM、不同插入顺序下不一致，
 * 会导致<b>同一个查询产生不同的 key</b>。因此这里统一先转成 {@code TreeMap} 排序再序列化。
 */
@Slf4j
@Service
public class SearchServiceImpl implements SearchService {

    private final IndexTypeConfigService indexTypeConfigService;
    private final EsSearchGateway esSearchGateway;
    private final CacheService cacheService;
    private final KumaSearchProperties properties;
    private final HotWordService hotWordService;
    private final QueryBuilder queryBuilder;
    private final SearchLogMapper searchLogMapper;
    private final ObjectMapper objectMapper;
    private final Executor searchLogExecutor;

    public SearchServiceImpl(IndexTypeConfigService indexTypeConfigService,
                             EsSearchGateway esSearchGateway,
                             CacheService cacheService,
                             KumaSearchProperties properties,
                             HotWordService hotWordService,
                             QueryBuilder queryBuilder,
                             SearchLogMapper searchLogMapper,
                             ObjectMapper objectMapper,
                             @Qualifier("searchLogExecutor") Executor searchLogExecutor) {
        this.indexTypeConfigService = indexTypeConfigService;
        this.esSearchGateway = esSearchGateway;
        this.cacheService = cacheService;
        this.properties = properties;
        this.hotWordService = hotWordService;
        this.queryBuilder = queryBuilder;
        this.searchLogMapper = searchLogMapper;
        this.objectMapper = objectMapper;
        this.searchLogExecutor = searchLogExecutor;
    }

    /** 用于「猜你想搜」的兜底建议条数。只取一条，多了没意义。 */
    private static final int CORRECT_HINT_SIZE = 1;

    /** 发布状态字段名。与 {@code t_search_content.status}、各类型 {@code field_config} 里的声明逐字对应。 */
    private static final String FIELD_STATUS = "status";

    /** 已发布（审核通过）。{@code t_search_content.status} 的取值。 */
    private static final String STATUS_PUBLISHED = "1";

    /**
     * 已经就"该类型没声明 status 字段"告警过的 typeCode，用于去重。
     * 没有它的话，检索是高频路径，一个配置缺失的类型会让这条 WARN 刷满日志。
     */
    private final Set<String> warnedMissingStatusField = ConcurrentHashMap.newKeySet();

    // ==================================================================================
    //  检索主流程
    // ==================================================================================

    @Override
    public SearchResponseDTO search(SearchRequestDTO request, Long userId, String clientIp) {
        long startedAt = System.currentTimeMillis();

        if (request == null || !StringUtils.hasText(request.getTypeCode())) {
            throw BizException.paramInvalid("typeCode 不能为空");
        }

        IndexDefinition def = indexTypeConfigService.resolveDefinition(request.getTypeCode());
        SearchParam param = toSearchParam(request, def);

        String cacheKey = CacheKeys.search(def.getTypeCode(), String.valueOf(def.getVersion()),
                fingerprint(request));

        SearchResponseDTO response;
        try {
            response = cacheService.get(cacheKey, properties.getCache().getSearchTtlSeconds(),
                    SearchResponseDTO.class, () -> doSearch(def, param));
        } catch (BizException e) {
            // 业务异常必须原样抛出，不能顺手降级重试。
            //
            // 【为什么这个分支是必要的】loader（doSearch）是在这个 try 块里被调用的，
            // 因此它抛出的**任何**异常都会落到下面的 catch (Exception) 里，
            // 被当成"缓存层故障"记一条日志、然后再执行一遍。
            // 那样做的后果有两个：一是日志把"参数非法""字段不在白名单""深分页缺少游标"
            // 这些明确可修的错都写成"缓存异常"，排查时会被引到完全错误的方向；
            // 二是整个检索会**执行两遍** —— 浪费的那一次同样会打满 ES 连接池。
            throw e;
        } catch (Exception e) {
            // 到这里才是真正的缓存层故障。缓存是加速器不是单点，
            // 所以降级为直查 ES，让检索继续可用。
            log.warn("检索缓存异常，降级为直查 ES，type={}，原因={}", def.getTypeCode(), e.getMessage());
            response = doSearch(def, param);
        }

        long cost = System.currentTimeMillis() - startedAt;

        // ===== 埋点放在缓存之后，这是关键 =====
        // 如果放进被缓存的 loader 里，命中缓存时就不会记录，
        // 结果是"搜得越多的词越不上榜"——热词榜恰好会漏掉真正的热词。
        recordSearch(def, request, response, userId, clientIp, cost);

        return response;
    }

    /**
     * 实际执行检索并转换成对外 DTO。
     *
     * <p>这个方法的结果会被缓存，因此它<b>不能</b>依赖 userId / clientIp 这类
     * 因人而异的输入，也不能有任何副作用。
     */
    private SearchResponseDTO doSearch(IndexDefinition def, SearchParam param) {
        SearchResult result = esSearchGateway.search(def, param);

        List<HitDTO> hits = result.getHits().stream()
                .map(this::toHitDTO)
                .toList();

        SearchResponseDTO response = SearchResponseDTO.builder()
                .hits(hits)
                .total(result.getPage() == null ? null : result.getPage().getTotal())
                .pageNum(result.getPage() == null ? null : result.getPage().getPageNum())
                .pageSize(result.getPage() == null ? param.getPageSize() : result.getPage().getPageSize())
                .pages(result.getPage() == null ? null : result.getPage().getPages())
                .nextCursor(result.getPage() == null ? null : result.getPage().getNextCursor())
                .cursorMode(result.isCursorMode())
                .facets(toFacetDTOs(def, result.getFacets()))
                .tookMillis(result.getTookMillis())
                .partial(result.isPartial())
                .build();

        // 无结果时补一个"猜你想搜"，避免用户面对一个空白页面无从下手。
        if (hits.isEmpty() && StringUtils.hasText(param.getKeyword())) {
            response.setCorrectedKeyword(hintKeyword(def, param.getKeyword()));
        }

        return response;
    }

    /**
     * 无结果时的兜底建议。
     *
     * <h3>这<b>不是</b>真正的拼写纠错</h3>
     * 真正的纠错（"huawei shouji" → "华为手机"）需要基于编辑距离的 term suggester，
     * 那要求在索引里额外维护一个词项词典，属于后续迭代。
     * 这里做的是<b>前缀联想兜底</b>：用用户输入去查 completion 建议索引。
     * 它对"输入到一半就回车"这类场景有效，对真正的拼写错误无能为力 ——
     * 这个边界必须写清楚，否则会被误认为纠错能力已经具备。
     *
     * <p>返回 null 表示没有任何可给的建议，前端此时应当展示"没有找到相关内容 + 筛选建议"，
     * 而不是硬塞一个无关的词。
     */
    private String hintKeyword(IndexDefinition def, String keyword) {
        try {
            List<SuggestDTO> suggestions =
                    esSearchGateway.suggest(def, keyword, CORRECT_HINT_SIZE);
            if (suggestions.isEmpty()) {
                return null;
            }
            String hint = suggestions.get(0).getText();
            // 建议词与原文相同时没有提示价值
            return hint != null && !hint.equalsIgnoreCase(keyword) ? hint : null;
        } catch (Exception e) {
            log.debug("兜底建议查询失败，忽略，type={}，原因={}", def.getTypeCode(), e.getMessage());
            return null;
        }
    }

    // ==================================================================================
    //  DTO <-> 内部模型
    // ==================================================================================

    /**
     * DTO → 内部检索参数。
     *
     * <p>不在这里做字段白名单校验 —— 那是 {@link QueryBuilder} 的职责。
     * 理由：校验必须贴着 DSL 构造做。只要存在一条绕过本方法直接 gateway 的路径
     * （定时任务、导出、将来的内部 RPC），放在这一层的校验就形同虚设。
     *
     * <p><b>但 {@code status} 的注入放在这里</b>，与上面那条原则不冲突：
     * 它不是"校验调用方传了什么"，而是"给查询补上一个调用方无法决定的常量条件"，
     * 语义上属于「构造这次查询」而不是「检查这次查询」。放进 {@link QueryBuilder}
     * 反而会被它当成"调用方传入的过滤条件"而允许被覆盖。详见 {@link #withPublishedFilter}。
     */
    private SearchParam toSearchParam(SearchRequestDTO request, IndexDefinition def) {
        SearchParam.SearchParamBuilder builder = SearchParam.builder()
                .typeCode(request.getTypeCode())
                .keyword(request.getKeyword())
                .searchFields(request.getSearchFields())
                .filters(withPublishedFilter(request.getFilters(), def))
                .highlightFields(request.getHighlightFields())
                .pageNum(request.getPageNum())
                .pageSize(request.getPageSize())
                .cursor(request.getCursor())
                .useCursor(request.getUseCursor())
                .minScore(request.getMinScore())
                .trackTotalHits(request.getTrackTotalHits())
                .fuzzy(request.getFuzzy())
                .synonym(request.getSynonym())
                .pinyin(request.getPinyin())
                .aggSize(request.getAggSize() != null
                        ? request.getAggSize() : properties.getSearch().getFacetSize());

        if (request.getRangeFilters() != null && !request.getRangeFilters().isEmpty()) {
            builder.rangeFilters(request.getRangeFilters().stream()
                    .map(r -> SearchParam.RangeFilter.builder()
                            .field(r.getField())
                            .gte(r.getGte())
                            .lte(r.getLte())
                            .gt(r.getGt())
                            .lt(r.getLt())
                            .build())
                    .toList());
        }

        if (request.getFacets() != null && !request.getFacets().isEmpty()) {
            builder.facets(request.getFacets().stream()
                    .map(f -> SearchParam.FacetSpec.builder()
                            .field(f.getField())
                            .size(f.getSize())
                            .build())
                    .toList());
        }

        if (request.getSorts() != null && !request.getSorts().isEmpty()) {
            builder.sorts(request.getSorts().stream()
                    .map(s -> SearchParam.SortSpec.builder()
                            .field(s.getField())
                            .order(s.getOrder())
                            .mode(s.getMode())
                            .build())
                    .toList());
        }

        return builder.build();
    }

    private HitDTO toHitDTO(EsHit hit) {
        return HitDTO.builder()
                .id(hit.getId())
                .score(hit.getScore())
                .fields(hit.getSource())
                .highlights(hit.getHighlights())
                .summary(hit.getSummary())
                .build();
    }

    private List<FacetDTO> toFacetDTOs(IndexDefinition def, List<FacetResult> facets) {
        if (facets == null || facets.isEmpty()) {
            return List.of();
        }
        List<FacetDTO> result = new ArrayList<>(facets.size());
        for (FacetResult facet : facets) {
            List<FacetDTO.BucketDTO> buckets = facet.getBuckets() == null
                    ? List.of()
                    : facet.getBuckets().stream()
                    .map(b -> FacetDTO.BucketDTO.builder()
                            .key(b.getKey())
                            .docCount(b.getDocCount())
                            .build())
                    .toList();

            result.add(FacetDTO.builder()
                    .field(facet.getField())
                    // 展示名从类型配置里取，前端不必维护一份"字段名 → 中文"的映射表；
                    // 新增类型时前端零改动，这是配置驱动在多类型场景下的直接收益。
                    .label(def.field(facet.getField())
                            .map(FieldConfig::getLabel)
                            .filter(StringUtils::hasText)
                            .orElse(facet.getField()))
                    .total(facet.getTotal())
                    .buckets(buckets)
                    .build());
        }
        return result;
    }

    // ==================================================================================
    //  埋点
    // ==================================================================================

    /**
     * 记录热词与搜索日志。
     *
     * <p>两件事的"可失败程度"不同，因此走不同的路径：
     * <ul>
     *   <li><b>热词</b>写 Redis ZSET，纳秒级，直接同步做（实现内部已吞异常）；</li>
     *   <li><b>日志</b>要落 MySQL，在 500 QPS 下同步写就是 500 次/s 的插入，
     *       会把连接池占满并拖慢检索本身。因此丢进独立线程池异步写。</li>
     * </ul>
     */
    private void recordSearch(IndexDefinition def, SearchRequestDTO request,
                              SearchResponseDTO response, Long userId,
                              String clientIp, long costMillis) {
        String keyword = request.getKeyword();
        if (StringUtils.hasText(keyword)) {
            // 带上 clientIp：搜索匿名可用之后，热词榜需要按 IP 去重才不至于被单人刷满，
            // 见 HotWordService#record(String, String, Long, String)
            hotWordService.record(def.getTypeCode(), keyword, userId, clientIp);
        }

        if (!properties.getSearch().isSearchLogEnabled()) {
            return;
        }

        SearchLog logEntry = SearchLog.builder()
                .userId(userId)
                .typeCode(def.getTypeCode())
                .keyword(keyword)
                .normalized(hotWordService.normalize(keyword))
                .resultCount(response.getTotal() == null
                        ? response.getHits().size()
                        : response.getTotal().intValue())
                .costMs((int) costMillis)
                .ip(clientIp)
                // 从 MDC 取，键名与 RequestIdFilter 共用 TraceConstants 里的常量。
                // 取不到时（例如内部定时任务调用本方法，不经过 Servlet 过滤器）允许为 null ——
                // 那一刻确实没有"请求"可言，编一个假 ID 只会污染这一列的可信度。
                .requestId(org.slf4j.MDC.get(TraceConstants.REQUEST_ID))
                .createTime(LocalDateTime.now())
                .build();

        try {
            searchLogExecutor.execute(() -> {
                try {
                    searchLogMapper.insert(logEntry);
                } catch (Exception e) {
                    // 日志写失败绝对不能影响搜索。这个线程池用的是 DiscardPolicy，
                    // 这里再兜一层是为了让"日志表挂了"这件事在应用日志里可见。
                    log.warn("搜索日志落库失败，type={}，原因={}", logEntry.getTypeCode(), e.getMessage());
                }
            });
        } catch (Exception e) {
            // 线程池已满或已关闭：丢弃日志，不影响用户。
            log.debug("搜索日志异步队列拒绝任务，已丢弃，原因={}", e.getMessage());
        }
    }

    // ==================================================================================
    //  参数指纹
    // ==================================================================================

    /**
     * 计算检索参数的稳定指纹。
     *
     * <p>稳定性来自两点：<b>只纳入影响结果的字段</b>，以及<b>对 Map 排序</b>。
     * 直接用 {@code request.toString()}（Lombok 生成的）看似省事，但 Lombok 的 toString
     * 对 HashMap 字段是按其迭代顺序输出的，同一个逻辑请求在两次调用里可能得到不同的字符串 ——
     * 缓存永远不命中，而且不会报任何错。
     *
     * <p>指纹长度取 SHA-256 的前 16 个十六进制字符（64 bit）。
     * 这个长度下，即使每秒产生百万级不同查询，碰撞概率也远低于其他故障源的量级；
     * 而 key 短一点对 Redis 的内存占用是实实在在的收益（key 本身也会占内存）。
     */
    String fingerprint(SearchRequestDTO request) {
        StringBuilder sb = new StringBuilder(256);

        // 逐段拼接，每段带前缀分隔，避免"字段值里恰好含有分隔符"导致的歧义碰撞
        sb.append("t=").append(request.getTypeCode()).append('|');
        sb.append("k=").append(normalizeForKey(request.getKeyword())).append('|');
        sb.append("sf=").append(joinSorted(request.getSearchFields())).append('|');
        sb.append("hf=").append(joinSorted(request.getHighlightFields())).append('|');
        sb.append("f=").append(stableMapString(request.getFilters())).append('|');
        sb.append("rf=").append(rangeString(request.getRangeFilters())).append('|');
        sb.append("fa=").append(facetString(request.getFacets())).append('|');
        sb.append("so=").append(sortString(request.getSorts())).append('|');
        sb.append("pn=").append(request.getPageNum()).append('|');
        sb.append("ps=").append(request.getPageSize()).append('|');
        sb.append("cu=").append(request.getCursor()).append('|');
        sb.append("uc=").append(request.getUseCursor()).append('|');
        sb.append("ms=").append(request.getMinScore()).append('|');
        // trackTotalHits 会改变 total 的取值（精确值 vs 估算下界），必须进指纹，
        // 否则"只差这一个开关"的两次请求会共用缓存条目，其中一次拿到的是另一种口径的 total。
        // 【维护提醒】这个方法是手工逐字段拼的，往 SearchRequestDTO 加字段时**务必**同步加到这里，
        // 漏加不会报错，只会让两个不同的查询共用缓存 —— 表现为"搜索结果偶尔对不上"。
        sb.append("tt=").append(request.getTrackTotalHits()).append('|');
        sb.append("fz=").append(request.getFuzzy()).append('|');
        sb.append("sy=").append(request.getSynonym()).append('|');
        sb.append("py=").append(request.getPinyin()).append('|');
        sb.append("ag=").append(request.getAggSize());

        return sha256Hex(sb.toString()).substring(0, 16);
    }

    /** 关键词大小写与首尾空白不影响检索结果（IK 分词后统一小写），因此归一化后参与指纹。 */
    private String normalizeForKey(String keyword) {
        return keyword == null ? "" : keyword.trim().toLowerCase();
    }

    private String joinSorted(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(String::compareTo);
        return String.join(",", sorted);
    }

    /** filters 先转 TreeMap 保证顺序稳定，再拼接。 */
    private String stableMapString(Map<String, List<String>> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(map).forEach((key, values) -> {
            if (key == null || values == null || values.isEmpty()) {
                return;
            }
            // 同一字段的多个取值之间是 OR，顺序不影响结果，因此也排序
            List<String> sorted = new ArrayList<>(values);
            sorted.sort(String::compareTo);
            sb.append(key).append(':').append(String.join(",", sorted)).append(';');
        });
        return sb.toString();
    }

    private String rangeString(List<SearchRequestDTO.RangeFilterDTO> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SearchRequestDTO.RangeFilterDTO r : ranges) {
            sb.append(r.getField()).append(':')
                    .append(r.getGte()).append(',')
                    .append(r.getLte()).append(',')
                    .append(r.getGt()).append(',')
                    .append(r.getLt()).append(';');
        }
        return sb.toString();
    }

    private String facetString(List<SearchRequestDTO.FacetDTO> facets) {
        if (facets == null || facets.isEmpty()) {
            return "";
        }
        // 聚合字段的<b>顺序会影响响应中 facets 数组的顺序</b>，因此这里不能排序 ——
        // 排序会让 [品牌, 价格] 与 [价格, 品牌] 共用一条缓存，返回的数组顺序与请求不符。
        StringBuilder sb = new StringBuilder();
        for (SearchRequestDTO.FacetDTO f : facets) {
            sb.append(f.getField()).append(':').append(f.getSize()).append(';');
        }
        return sb.toString();
    }

    private String sortString(List<SearchRequestDTO.SortDTO> sorts) {
        if (sorts == null || sorts.isEmpty()) {
            return "";
        }
        // 同上，排序字段的顺序决定优先级，必须原样参与指纹。
        StringBuilder sb = new StringBuilder();
        for (SearchRequestDTO.SortDTO s : sorts) {
            sb.append(s.getField()).append(':').append(s.getOrder()).append(':')
                    .append(s.getMode()).append(';');
        }
        return sb.toString();
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境被破坏。
            // 退化为 hashCode 会导致缓存命中率下降，但不会导致错误结果（key 不同只是不命中）。
            log.error("计算检索参数指纹失败，降级为 hashCode", e);
            return Integer.toHexString(input.hashCode());
        }
    }

    /** 供调试接口使用：把内部检索参数渲染成 JSON，便于在 Kibana 复现问题。 */
    public String explainParam(SearchRequestDTO request) {
        try {
            // 这里也解析 def 而不是传 null：调试输出必须与真实查询逐字一致，
            // 否则会出现"explain 出来的 DSL 能在 Kibana 跑出结果，线上却搜不到"——
            // 排错时最忌讳让工具说的和系统做的不一样。
            SearchParam param = toSearchParam(request, resolveDefinitionQuietly(request));
            return objectMapper.writeValueAsString(param);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /** 供上层判断是否有有效检索条件（决定是否记录日志、是否展示"无关键词"的空状态）。 */
    public boolean hasCondition(SearchRequestDTO request) {
        return queryBuilder.hasSearchCondition(toSearchParam(request, resolveDefinitionQuietly(request)));
    }

    /**
     * 解析类型定义；解析不出来时返回 {@code null}，由 {@link #withPublishedFilter} 按"不加过滤"处理。
     *
     * <p>只给上面两个辅助方法用：它们的作用是**解释**一次查询，而不是执行它 ——
     * 类型停用/不存在时这里抛异常，调用方就只能看到一个 error 字符串，得不到任何有用信息。
     * 主链路 {@link #search} 不走这条路，它照旧让异常原样抛出。
     */
    private IndexDefinition resolveDefinitionQuietly(SearchRequestDTO request) {
        try {
            return indexTypeConfigService.resolveDefinition(request.getTypeCode());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把 {@code status = 1}（已发布）注入过滤条件，返回一个**新 Map**，并保证调用方覆盖不了它。
     *
     * <h3>为什么必须放在服务端</h3>
     * 写入链路（Outbox → {@code IndexEventHandler}）<b>不按 status 过滤</b>：
     * 任何状态的 {@code t_search_content} 行都会被 upsert 进 ES，包括草稿（0）与已驳回（2）。
     * 因此「未发布的内容不该被搜到」这件事<b>完全依赖查询侧的过滤</b>，
     * 在这里漏掉就是草稿与驳回内容对公网可见。{@code WebSearchServiceImpl} 早已这么做，
     * 本方法把同一条约束补到站内检索上 —— 那条链路上原本一道过滤都没有。
     *
     * <h3>为什么调用方覆盖不了</h3>
     * 三步都不能省：
     * <ol>
     *   <li><b>新建 Map</b>，不碰调用方传进来的集合 —— 否则会污染调用方的 DTO，
     *       而 {@code fingerprint()}（缓存 key）与 {@code recordSearch}（埋点）读的都是它，
     *       等于把服务端行为写进了调用方的数据；</li>
     *   <li>复制调用方条件时<b>跳过 {@code status} 键</b> —— 传了也不认，
     *       不靠"后 put 覆盖"来兜底（那种写法在改用 LinkedHashMap 合并、或将来有人调整顺序时就会失效）；</li>
     *   <li>最后再 put 服务端的值。</li>
     * </ol>
     *
     * <h3>为什么先判断字段是否存在</h3>
     * 索引 mapping 是 {@code dynamic: false}，只有 {@code field_config} 里声明过的字段才可查。
     * 若某个类型没声明 {@code status}，注入这个条件会让 ES 去查一个不存在的字段 ——
     * <b>结果是零条命中，而且不报错</b>：接口正常返回、日志干净，只是"这个类型搜什么都是空的"。
     * 所以这里按 {@code def} 判断：没声明就<b>不加</b>过滤（此时该类型本就没有发布态可言），
     * 并打一条 {@code WARN}（每个类型只打一次，避免刷屏）提醒配置缺失。
     */
    private Map<String, List<String>> withPublishedFilter(Map<String, List<String>> callerFilters,
                                                          IndexDefinition def) {
        if (def == null || !def.hasField(FIELD_STATUS)) {
            // def 为 null 只可能来自 explainParam / hasCondition（解析失败），
            // 主链路的 def 一定非空 —— 那里解析失败会直接抛业务异常。
            if (def != null && warnedMissingStatusField.add(def.getTypeCode())) {
                log.warn("类型 {} 的 field_config 未声明 {} 字段，无法按发布状态过滤，"
                                + "草稿与已驳回的内容也会出现在检索结果里。"
                                + "若这个类型确实需要发布态，请在其 field_config 中补上该字段后重建索引",
                        def.getTypeCode(), FIELD_STATUS);
            }
            return callerFilters;
        }

        Map<String, List<String>> merged = new LinkedHashMap<>();
        if (callerFilters != null) {
            callerFilters.forEach((key, value) -> {
                if (!FIELD_STATUS.equals(key)) {
                    merged.put(key, value);
                }
            });
        }
        merged.put(FIELD_STATUS, List.of(STATUS_PUBLISHED));
        return merged;
    }
}
