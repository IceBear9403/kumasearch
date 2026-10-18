package com.kuma.search.es.gateway;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.CompletionSuggestOption;
import co.elastic.clients.elasticsearch.core.search.FieldSuggester;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.Suggestion;
import co.elastic.clients.json.JsonData;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.PageResult;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.dto.resp.SuggestDTO;
import com.kuma.search.es.builder.AggBuilder;
import com.kuma.search.es.builder.HighlightBuilder;
import com.kuma.search.es.builder.QueryBuilder;
import com.kuma.search.es.builder.SortBuilder;
import com.kuma.search.es.mapping.FieldConfig;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.es.model.CursorCodec;
import com.kuma.search.es.model.EsHit;
import com.kuma.search.es.model.FacetResult;
import com.kuma.search.es.model.SearchParam;
import com.kuma.search.es.model.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索能力的唯一入口。业务代码永远不直接使用 {@code ElasticsearchClient}。
 *
 * <p>收敛在这一层的好处：将来 ES 集群化（换多节点 transport）、升级 ES 大版本、
 * 接入熔断/限流、或把检索拆成独立服务时，改动都只发生在这里，
 * 上层的 {@code SearchService} 与 Controller 不受影响。
 *
 * <h3>分页策略（这是本类最需要理解的部分）</h3>
 * ES 的 {@code from + size} 深分页有一个硬约束：协调节点必须从每个分片取回
 * {@code from + size} 条文档再归并排序，内存与耗时随页码线性增长。
 * 翻到第 1000 页时，为返回 10 条结果要搬运数万条文档。
 *
 * <p>因此本项目采用双模式：
 * <ul>
 *   <li><b>浅分页</b>（{@code from + size} 在 {@code max-result-window} 之内）：
 *       返回精确 total，支持随机跳页。用户在第 1~5 页的浏览行为占绝大多数，这条路径够用。</li>
 *   <li><b>游标分页</b>（{@code search_after}）：越过窗口后自动切换，
 *       只返回 nextCursor，不支持跳页。代价换来的是「翻到第 10000 页也是常数开销」。</li>
 * </ul>
 *
 * <p><b>不使用 PIT 的取舍</b>：严格的 {@code search_after} 应当配合 Point-In-Time
 * 以保证翻页期间视图一致。本项目选择不开启 PIT，理由是：<br>
 * 一是 PIT 会在搜索上下文中持有分片资源，忘记关闭会持续占用堆内存和文件句柄，
 * 而「忘记关闭」在实践中非常常见；<br>
 * 二是我们的排序链<b>总是以唯一主键收尾</b>（见 {@code SortBuilder#appendTieBreaker}），
 * 只要主键唯一，翻页就不会漏文档或重复；<br>
 * 三是索引写入引起的偏移只会导致「新文档出现在后续页」，对搜索场景可以接受。<br>
 * 若将来需要严格一致的深翻页（如数据导出），应改用 PIT，本类已预留开关位置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsSearchGateway {

    private final ElasticsearchClient client;
    private final KumaSearchProperties properties;

    private final QueryBuilder queryBuilder;
    private final SortBuilder sortBuilder;
    private final HighlightBuilder highlightBuilder;
    private final AggBuilder aggBuilder;
    private final CursorCodec cursorCodec;

    /** 建议索引中 completion 字段的名称，与 {@code MappingGenerator#buildSuggestIndexJson} 保持一致。 */
    private static final String SUGGEST_FIELD = "suggest";
    /** 建议 query 的名称，用于从响应里取回结果。 */
    private static final String SUGGEST_NAME = "kuma-suggest";
    /** 建议文档里存放"展示用标题"的字段名，同样与建议索引 mapping 保持一致。 */
    private static final String SUGGEST_SOURCE_TITLE = "title";

    // ==================================================================================
    //  检索
    // ==================================================================================

    /**
     * 执行检索。
     *
     * @param def   已解析的类型定义
     * @param param 检索参数（字段合法性已在 builder 中校验）
     */
    public SearchResult search(IndexDefinition def, SearchParam param) {
        int pageSize = normalizePageSize(param);
        boolean cursorMode = shouldUseCursor(param);
        String indexName = def.getAlias();

        List<co.elastic.clients.elasticsearch._types.SortOptions> sorts =
                sortBuilder.build(def, param);

        SearchRequest.Builder request = new SearchRequest.Builder()
                .index(indexName)
                .query(queryBuilder.build(def, param))
                .size(pageSize)
                .sort(sorts)
                // 字段排序时 ES 默认不算分（_score 为 null），而本站检索的排序链尾部
                // 总会追加主键兜底排序，所以**常规检索的每条命中都没有评分**。
                // 这不只是展示问题：后端「忽略 null」的序列化配置会让 score 键整个消失，
                // 前端 score.toFixed(2) 因此抛 TypeError，整块结果渲染中止。
                // 见 SortBuilder#needsTrackScores 的说明。
                .trackScores(sortBuilder.needsTrackScores(sorts))
                // 关闭精确计数能让 ES 跳过一次全分片的计数遍历。
                // 游标模式下本来就不返回 total，这里顺带关掉是免费的优化。
                .trackTotalHits(t -> t.enabled(
                        !cursorMode && Boolean.TRUE.equals(param.getTrackTotalHits())));

        Highlight highlight = highlightBuilder.build(def, param);
        if (highlight != null) {
            request.highlight(highlight);
        }

        Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregation> aggs =
                aggBuilder.build(def, param);
        if (!aggs.isEmpty()) {
            request.aggregations(aggs);
        }

        if (param.getMinScore() != null) {
            request.minScore(param.getMinScore().doubleValue());
        }

        if (cursorMode) {
            List<FieldValue> searchAfter = cursorCodec.decode(param.getCursor());
            if (!searchAfter.isEmpty()) {
                request.searchAfter(searchAfter);
            } else if (!Boolean.TRUE.equals(param.getUseCursor())) {
                // 走到这里说明是 {@link #shouldUseCursor} 的**自动切换**分支：
                // 第 pageNum 页的 from+size 已越过 max_result_window，调用方却没有给 cursor。
                //
                // 【曾经的真实缺陷】这里以前是"什么都不设"，于是请求里既没有 search_after
                // 也没有 from，ES 就按默认从头返回 —— 也就是**静默地把第 1 页的数据当成第 1001 页返回**，
                // 不报错、日志里也看不出来，前端表现为"翻到深页突然跳回第一页"。
                // 用户会以为数据错了，而排查的人会去怀疑数据同步。
                //
                // 正确做法是明确告诉调用方"这一页你拿不到，请改用游标"。深分页不是靠调大
                // max_result_window 能解决的（那只会把协调节点的堆一起拖下水），
                // 唯一正确的姿势是先请求靠前的页拿到 nextCursor，再用游标往后翻。
                throw BizException.of(ResultCode.PARAM_INVALID,
                        "第 " + normalizePageNum(param) + " 页已超出浅分页窗口（max_result_window="
                                + properties.getElasticsearch().getMaxResultWindow()
                                + "），无法直接定位。请先请求靠前的页码拿到响应中的 nextCursor，"
                                + "再用 cursor 参数继续向后翻页；游标模式下不支持跳页。");
            }
        } else {
            int pageNum = normalizePageNum(param);
            request.from((pageNum - 1) * pageSize);
        }

        SearchResponse<JsonData> response;
        try {
            response = client.search(request.build(), JsonData.class);
        } catch (Exception e) {
            if (isIndexMissing(e)) {
                // 索引根本还没建（部署时漏了 create-index，见 docs/07-deployment.md §四 步骤 7）。
                // 这里**降级成空结果 + partial**，而不是抛 50002。
                //
                // 取舍：这类故障与「ES 挂了」「查询写错了」有本质区别 —— 它是**可预期的配置状态**
                // （新类型刚配好、索引还没建），而且同一个类型下**每一个**查询都会这样。
                // 报 50002 的后果是所有访客在页面上看到「搜索执行失败，请稍后重试」，
                // 而「稍后重试」永远不会成功，唯一会因此受益的人（管理员）其实看的是日志。
                // 与 suggest 的做法一致：那一条链路遇到任何异常也是返回空列表 + WARN。
                //
                // 代价是「搜过了没东西」与「这个类型还没建索引」在结果上都是 0 条，
                // 因此 partial=true 必须一起给出去 —— 前端据此把空态文案从
                // 「没有找到相关内容」换成「该类型暂时无法检索」，别让用户以为库里真的没数据。
                // WARN 里点名了修复动作，运维 grep「索引尚未创建」能直接拿到结论。
                log.warn("索引尚未创建，按空结果降级返回：type={}，index={}。"
                                + "修复：POST /api/admin/index-type/{}/create-index"
                                + "（已有数据时随后再 rebuild，顺序不能反）",
                        def.getTypeCode(), def.physicalIndexName(), def.getTypeCode());
                return emptyResult(param, pageSize, cursorMode);
            }
            log.error("检索执行失败，type={}，keyword={}，原因={}",
                    def.getTypeCode(), param.getKeyword(), e.getMessage());
            throw BizException.of(ResultCode.ES_QUERY_FAILED, "搜索执行失败，请稍后重试", e);
        }

        // ===== 解析命中 =====
        List<EsHit> hits = new ArrayList<>(response.hits().hits().size());
        List<FieldValue> lastSortValues = null;
        for (Hit<JsonData> hit : response.hits().hits()) {
            hits.add(toEsHit(hit, def, param));
            lastSortValues = hit.sort();
        }

        // ===== 解析分页 =====
        PageResult<EsHit> page;
        if (cursorMode) {
            // 只有「取满了整页」才可能还有下一页；不足一页说明已到末尾。
            String nextCursor = hits.size() == pageSize ? cursorCodec.encode(lastSortValues) : null;
            page = PageResult.ofCursor(hits, pageSize, nextCursor);
        } else {
            long total = response.hits().total() != null
                    ? response.hits().total().value()
                    : hits.size();
            page = PageResult.of(hits, total, normalizePageNum(param), pageSize);
        }

        // ===== 解析聚合 =====
        List<FacetResult> facets = parseFacets(response, def);

        return SearchResult.builder()
                .hits(hits)
                .page(page)
                .facets(facets)
                .tookMillis(response.took())
                .cursorMode(cursorMode)
                // ES 在部分分片失败或查询超时时仍会返回结果，但 timed_out 为 true。
                // 不告知用户的话，他们会以为「就这些结果」，这是很糟糕的体验。
                .partial(Boolean.TRUE.equals(response.timedOut()))
                .build();
    }

    // ==================================================================================
    //  索引缺失的降级
    // ==================================================================================

    /** ES 在目标索引（本项目里是别名）不存在时返回的错误类型。 */
    private static final String INDEX_NOT_FOUND = "index_not_found_exception";

    /**
     * 判断异常链里有没有「索引不存在」。
     *
     * <p>两条判据都要有：<b>类型判据</b>（{@code ElasticsearchException} 的结构化错误类型）是
     * 首选，它不受消息格式变化影响；<b>消息判据</b>是兜底，因为异常在往上抛的过程中可能被
     * 包成 {@code TransportException}/{@code IOException} 而丢掉原始类型。
     * 消息里出现 {@code index_not_found_exception} 这个字面量是稳定的 —— 它是 ES 的错误类型名，
     * 不是给人读的措辞，ES 改文案不会动它。
     */
    private boolean isIndexMissing(Throwable e) {
        // 限深而不是「跟着 cause 一路走到底」：异常链理论上可以成环，
        // 不设界的遍历在那种情况下是死循环。
        for (int depth = 0; e != null && depth < 8; depth++, e = e.getCause()) {
            if (e instanceof ElasticsearchException es
                    && es.error() != null
                    && INDEX_NOT_FOUND.equals(es.error().type())) {
                return true;
            }
            if (e.getMessage() != null && e.getMessage().contains(INDEX_NOT_FOUND)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构造一个空结果，供索引缺失时降级返回。
     *
     * <p>{@code partial=true} 是这里<b>唯一</b>能把「空」与「没搜成」区分开的信号，
     * 不能省：省掉之后前端只能显示「没有找到相关内容」，而那句话的含义是
     * 「搜过了，库里没有」，与事实相反。
     *
     * <p>分页信息按调用方的模式构造（游标模式不返回 total），不能图省事都走浅分页 ——
     * 那会让游标模式下的响应里凭空多出一个 {@code total}，前端据此渲染出的分页条
     * 在「本来就没有下一页」的情况下依然显示页码。
     */
    private SearchResult emptyResult(SearchParam param, int pageSize, boolean cursorMode) {
        PageResult<EsHit> page = cursorMode
                ? PageResult.ofCursor(List.of(), pageSize, null)
                : PageResult.empty(normalizePageNum(param), pageSize);
        return SearchResult.builder()
                .hits(List.of())
                .page(page)
                .facets(List.of())
                .tookMillis(0L)
                .cursorMode(cursorMode)
                .partial(true)
                .build();
    }

    // ==================================================================================
    //  联想建议
    // ==================================================================================

    /**
     * 基于 completion suggester 的前缀联想。
     *
     * <p>查询打在<b>独立的建议索引</b>（{@code {alias}_suggest}）上，而不是主索引 ——
     * completion 字段的 FST 前缀树会常驻堆内存，建在主索引上等于让每个文档都贡献内存占用。
     * 详见 {@code MappingGenerator#buildSuggestIndexJson} 的说明。
     *
     * @param def    类型定义
     * @param prefix 用户已输入的前缀
     * @param size   返回条数
     */
    public List<SuggestDTO> suggest(IndexDefinition def, String prefix, int size) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }

        // 配置里没有字段声明 suggest=true 的类型，本就不该有联想索引，
        // 也就不该去查它 —— 否则每一次击键都会换来一条
        // 「no such index [kuma_xxx_suggest]」的 WARN，把日志刷满，
        // 还让真正需要关注的 WARN 被淹没。
        //
        // （这里曾经举 webpage 作例子。那个例子已经过时：webpage 的 title 后来开了 suggest，
        //  见 IndexDefinition#hasSuggestField 的注释。）
        //
        // 这个判断必须与建索引那一侧用同一个开关（IndexDefinition#hasSuggestField）：
        // 「建的条件」与「查的条件」不一致，正是"ES 索引与需求对不上"这类问题的来源 ——
        // 不管往哪边偏，结果都是一边以为有、另一边以为没有。
        if (!def.hasSuggestField()) {
            return List.of();
        }

        // 单独构造 FieldSuggester，而不是把四层 lambda 内联进 search 调用：
        // 这里的类型层次是 search → Suggester(容器) → FieldSuggester(单条建议) → CompletionSuggester，
        // 内联写法会产生四层嵌套括号，极易写错且几乎无法阅读。
        FieldSuggester fieldSuggester = FieldSuggester.of(su -> {
            su.prefix(prefix);
            su.completion(c -> c
                    .field(SUGGEST_FIELD)
                    .size(size)
                    // 去重：同一建议词可能来自多个文档，
                    // 不去重会让下拉框出现重复项。
                    .skipDuplicates(true));
            return su;
        });

        try {
            SearchResponse<JsonData> response = client.search(
                    s -> s.index(def.suggestIndexName())
                            // 只要展示用的 title。completion 建议会把命中文档的 _source
                            // 一并带回来，不限定字段的话整个文档都要多走一趟网络 ——
                            // 而下拉框只用得上这一个字段。
                            .source(src -> src.filter(f -> f.includes(SUGGEST_SOURCE_TITLE)))
                            .suggest(sg -> sg.suggesters(SUGGEST_NAME, fieldSuggester)),
                    JsonData.class);

            Map<String, List<Suggestion<JsonData>>> suggestMap = response.suggest();
            if (suggestMap == null || !suggestMap.containsKey(SUGGEST_NAME)) {
                return List.of();
            }

            // 按<b>展示文本</b>去重，而不是依赖 completion 的 skipDuplicates。
            // 原因：一个文档会被灌入多个 input（标题、标题主段、全拼、首字母），
            // 用户输入「jy」时命中的 input 是 "jy" 那条，而输入「jiaoyu」命中的是另一条 ——
            // skipDuplicates 比的是"被命中的 input"，两者不同，于是同一个标题会出现两次。
            // 这里按最终展示的标题去重，才是用户看到的"没有重复项"。
            Map<String, SuggestDTO> deduped = new LinkedHashMap<>();
            for (Suggestion<JsonData> suggestion : suggestMap.get(SUGGEST_NAME)) {
                if (!suggestion.isCompletion()) {
                    continue;
                }
                for (CompletionSuggestOption<JsonData> option : suggestion.completion().options()) {
                    String text = displayText(option);
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    deduped.putIfAbsent(text, SuggestDTO.builder()
                            .text(text)
                            .source("index")
                            // 权重取被命中 input 的分值。同一个标题会命中多条 input，
                            // 保留分值最高的那条（先到的分高，ES 按 score 排序返回）。
                            .weight(option.score() == null ? 0 : option.score().intValue())
                            .build());
                }
            }
            return new ArrayList<>(deduped.values());
        } catch (Exception e) {
            // 联想失败是降级场景，不该让输入框报错。返回空列表，前端的体验是「暂时没有建议」。
            //
            // 这里补一句修复指引：索引不存在是这条 WARN 最常见的原因（索引没建、或被手工删过），
            // 而"联想不灵"这个现象本身指不到那个结论上 —— 没有指引的话，看到日志的人会去查分词、
            // 查词典、查前缀匹配，唯独不会想到"这个索引根本不存在"。
            log.warn("联想查询失败，type={}，prefix={}，原因={}。"
                            + "若原因是 no such index [{}]，说明联想索引缺失，"
                            + "修复：POST /api/admin/index-type/{}/rebuild-suggest",
                    def.getTypeCode(), prefix, e.getMessage(), def.suggestIndexName(), def.getTypeCode());
            return List.of();
        }
    }

    /**
     * 一条建议应该显示成什么。
     *
     * <h3>为什么不能直接用 {@code option.text()}</h3>
     * completion suggester 返回的 {@code text} 是<b>被命中的那个 input</b>，不是文档的标题。
     * 而灌词时会为同一条内容灌入多个 input（标题、标题主段、全拼、首字母），
     * 于是用户输入「jy」看到的是「jy」，输入「jiaoyu」看到的是「jiaoyu」——
     * 下拉框里没有一个中文，联想就失去了意义。
     *
     * <p>正确做法是取建议文档 {@code _source} 里的标题（灌词时一并写入，见
     * {@code MappingGenerator#buildSuggestIndexJson} 对 {@code title} 字段的说明）。
     *
     * <p>取不到时退回 {@code option.text()} 而不是丢弃这条建议：宁可显示得难看，
     * 也不要让用户看到"有内容却什么都不提示"。会走到这个分支的情形是
     * 建议文档没带 title（历史数据、或将来有人改 mapping 忘了这个字段）。
     */
    private String displayText(CompletionSuggestOption<JsonData> option) {
        JsonData source = option.source();
        if (source != null) {
            try {
                Map<?, ?> fields = source.to(Map.class);
                Object title = fields == null ? null : fields.get(SUGGEST_SOURCE_TITLE);
                if (title != null && !String.valueOf(title).isBlank()) {
                    return String.valueOf(title);
                }
            } catch (Exception e) {
                // 解析建议文档的 _source 失败不该影响联想本身：退回 input 文本即可。
                log.debug("解析建议文档 _source 失败，退回被命中的 input 文本，原因={}", e.getMessage());
            }
        }
        return option.text();
    }

    // ==================================================================================
    //  存在性判断
    // ==================================================================================
    /**
     * 判断某个业务主键的文档是否存在于索引中。
     *
     * <p>用 {@code _id} 直接做 HEAD 请求，而不是查一次 {@code term} 过滤再数命中数：
     * 前者在 ES 内部是 O(1) 的段查找，后者要走一遍完整的查询链路。
     * 对账任务会对成千上万个 id 做这个判断，这个差别是可感知的。
     *
     * <p>查的是<b>物理索引名</b>而不是别名：对账的目的是验证"那个版本的索引里有没有这条文档"，
     * 用别名会在重建期间把两个版本混在一起，得出无意义的结论。
     *
     * @return 存在为 true；索引不存在或查询异常时返回 false（对账任务会把它们都记为"缺失"）
     */
    public boolean existsById(IndexDefinition def, String bizId) {
        if (bizId == null || bizId.isBlank()) {
            return false;
        }
        try {
            return client.exists(e -> e.index(def.physicalIndexName()).id(bizId)).value();
        } catch (Exception e) {
            log.warn("判断文档是否存在失败，index={}，id={}，原因={}",
                    def.physicalIndexName(), bizId, e.getMessage());
            return false;
        }
    }

    // ==================================================================================
    //  内部实现
    // ==================================================================================

    private EsHit toEsHit(Hit<JsonData> hit, IndexDefinition def, SearchParam param) {
        Map<String, Object> source = new LinkedHashMap<>();
        if (hit.source() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> converted = hit.source().to(Map.class);
                source.putAll(converted);
            } catch (Exception e) {
                log.warn("文档 {} 的 _source 转换失败: {}", hit.id(), e.getMessage());
            }
        }

        Map<String, List<String>> highlights = hit.highlight() == null
                ? Map.of()
                : new LinkedHashMap<>(hit.highlight());

        return EsHit.builder()
                .id(hit.id())
                .score(hit.score())
                .source(source)
                .highlights(highlights)
                .summary(buildSummary(source, highlights, def))
                .build();
    }

    /**
     * 生成结果摘要。
     *
     * <p>优先使用高亮片段 —— 它天然包含关键词上下文，比单纯截断开头对用户有用得多。
     * 没有高亮（例如纯过滤查询、或关键词命中的是别的字段）时，回退到按配置的
     * summaryFields 顺序取第一个有值的字段并截断。
     */
    private String buildSummary(Map<String, Object> source,
                                Map<String, List<String>> highlights,
                                IndexDefinition def) {
        if (!highlights.isEmpty()) {
            // 取第一个高亮字段的第一段
            for (List<String> fragments : highlights.values()) {
                if (fragments != null && !fragments.isEmpty()) {
                    return fragments.get(0);
                }
            }
        }

        List<String> summaryFields = def.getSummaryFields();
        if (summaryFields != null) {
            for (String field : summaryFields) {
                Object value = source.get(field);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return truncate(stripTags(String.valueOf(value)));
                }
            }
        }
        return null;
    }

    private String truncate(String text) {
        int max = properties.getSearch().getSummaryLength();
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }

    private String stripTags(String text) {
        return text.replaceAll("<[^>]+>", "");
    }

    /**
     * 解析 facet 聚合结果。
     *
     * <p>聚合名与字段名一一对应（见 {@code AggBuilder}），因此这里可以直接按键取回。
     */
    private List<FacetResult> parseFacets(SearchResponse<JsonData> response, IndexDefinition def) {
        Map<String, Aggregate> aggregations = response.aggregations();
        if (aggregations == null || aggregations.isEmpty()) {
            return List.of();
        }

        List<FacetResult> results = new ArrayList<>(aggregations.size());
        aggregations.forEach((field, aggregate) -> {
            if (!aggregate.isSterms()) {
                return;
            }
            List<FacetResult.Bucket> buckets = new ArrayList<>();
            long total = 0;
            for (StringTermsBucket bucket : aggregate.sterms().buckets().array()) {
                buckets.add(FacetResult.Bucket.builder()
                        .key(String.valueOf(bucket.key().stringValue()))
                        .docCount(bucket.docCount())
                        .build());
                total += bucket.docCount();
            }
            results.add(FacetResult.builder()
                    .field(field)
                    .total(total)
                    .buckets(buckets)
                    .build());
        });
        return results;
    }

    /**
     * 判断是否必须走游标分页。
     *
     * <p>两种情况：调用方显式要求；或 {@code from + size} 已越过浅分页窗口。
     * 后一种情况必须自动切换 —— 否则 ES 会直接返回
     * {@code Result window is too large} 错误，前端表现为搜索在第 100 页突然不可用。
     */
    private boolean shouldUseCursor(SearchParam param) {
        if (Boolean.TRUE.equals(param.getUseCursor())) {
            return true;
        }
        int pageNum = normalizePageNum(param);
        int pageSize = normalizePageSize(param);
        long offset = (long) (pageNum - 1) * pageSize;
        return offset + pageSize > properties.getElasticsearch().getMaxResultWindow();
    }

    private int normalizePageNum(SearchParam param) {
        Integer pageNum = param.getPageNum();
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    /**
     * 归一化每页条数。
     *
     * <p>上限在这里<b>再次</b>强制，而不是只依赖 Controller 的 {@code @Max} 注解：
     * 内部调用（定时任务、数据导出）不会经过 Controller 校验，
     * 一次 {@code pageSize=100000} 的误调用就足以让协调节点 OOM。
     */
    private int normalizePageSize(SearchParam param) {
        Integer pageSize = param.getPageSize();
        int max = properties.getElasticsearch().getMaxPageSize();
        if (pageSize == null || pageSize < 1) {
            return properties.getSearch().getDefaultPageSize();
        }
        return Math.min(pageSize, max);
    }

    /** 供上层做「是否已到末页」判断。 */
    public boolean hasMore(SearchResult result) {
        return result.getPage() != null && result.getPage().getNextCursor() != null;
    }

    /** 供 FieldConfig 的展示名解析复用。 */
    public String labelOf(IndexDefinition def, String field) {
        return def.field(field).map(FieldConfig::getLabel).orElse(field);
    }
}
