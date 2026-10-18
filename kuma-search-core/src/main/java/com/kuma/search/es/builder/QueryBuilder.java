package com.kuma.search.es.builder;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.json.JsonData;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.es.mapping.FieldConfig;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.es.model.SearchParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link SearchParam} 翻译成 ES 的 Query DSL。
 *
 * <h3>安全边界</h3>
 * 本类是所有用户输入进入 DSL 的<b>唯一通道</b>。因此字段白名单校验必须在这里做，
 * 而不是在 Controller 层 —— 只要有一条旁路能绕过 Controller 直接调 service，
 * 校验就形同虚设。校验失败的请求直接抛 {@code BizException}，不静默忽略。
 *
 * <h3>相关度设计</h3>
 * 权重（boost）在<b>查询期</b>施加而非索引期。索引期的 boost 一旦写进 mapping 就不可更改，
 * 调整权重必须重建整个索引；查询期权重可以随时热调，还能按用户分群做 A/B 实验。
 * 这是本项目把 boost 放在 {@code FieldConfig} 而不是 ES mapping 里的原因。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryBuilder {

    private final KumaSearchProperties properties;

    /**
     * 构造完整查询：关键词检索 + 各类过滤。
     *
     * <p>结构是 {@code bool { must: [全文检索], filter: [精确过滤, 范围过滤] }}。
     * 过滤条件放在 {@code filter} 而不是 {@code must} 里，有两个实际收益：
     * 一是 filter 子句不参与打分计算，可跳过相关度评分；二是 filter 结果会被缓存，
     * 相同过滤条件在多次查询间可以复用。
     */
    public Query build(IndexDefinition def, SearchParam param) {
        List<Query> mustClauses = new ArrayList<>();
        List<Query> filterClauses = new ArrayList<>();

        // 1. 关键词全文检索
        Query keywordQuery = buildKeywordQuery(def, param);
        if (keywordQuery != null) {
            mustClauses.add(keywordQuery);
        }

        // 2. 精确过滤
        validateFilters(def, param);
        if (param.getFilters() != null) {
            param.getFilters().forEach((field, values) -> {
                if (values == null || values.isEmpty()) {
                    return;
                }
                filterClauses.add(termQuery(def, field, values));
            });
        }

        // 3. 范围过滤
        if (param.getRangeFilters() != null) {
            for (SearchParam.RangeFilter range : param.getRangeFilters()) {
                Query q = rangeQuery(def, range);
                if (q != null) {
                    filterClauses.add(q);
                }
            }
        }

        // 空查询场景（无关键词无过滤）也要返回一个合法的查询，否则 ES 会对全量文档打分。
        if (mustClauses.isEmpty() && filterClauses.isEmpty()) {
            return Query.of(q -> q.matchAll(m -> m));
        }

        return Query.of(q -> q.bool(b -> {
            if (!mustClauses.isEmpty()) {
                b.must(mustClauses);
            }
            if (!filterClauses.isEmpty()) {
                b.filter(filterClauses);
            }
            // 至少要匹配一个 must 子句（只有一个时必须满足，语义上更直观）
            b.minimumShouldMatch("0");
            return b;
        }));
    }

    /**
     * 关键词检索：中文分词 + 拼音 + 模糊纠错，多条召回路径合并打分。
     *
     * <p>为什么用 {@code multi_match} 的 {@code best_fields} 而不是 {@code cross_fields}：
     * 本项目的典型场景是「标题命中比正文命中重要得多」，best_fields 取单字段最高分，
     * 更符合直觉；cross_fields 会把词打散到各字段求平均，适合「姓名 = 姓 + 名拆在两个字段」
     * 这类互补字段的场景，不是这里的需求。
     *
     * <p>拼音召回通过 {@code title.pinyin} / {@code title.fpy} 子字段实现 ——
     * 它们与主字段地位相同，只是权重略低（用户输入拼音时通常目标明确，不需要太高的权重去压过中文命中）。
     */
    private Query buildKeywordQuery(IndexDefinition def, SearchParam param) {
        String keyword = param.getKeyword();
        if (keyword == null || keyword.isBlank()) {
            return null;
        }

        List<String> fields = resolveSearchFields(def, param);
        if (fields.isEmpty()) {
            return null;
        }

        // 带 boost 的字段列表：中文主字段用配置权重，拼音子字段用配置权重的 0.6 倍。
        List<String> boostedFields = new ArrayList<>();
        for (String field : fields) {
            FieldConfig fc = def.field(field).orElse(null);
            float boost = fc != null && fc.getBoost() != null ? fc.getBoost() : 1.0f;
            boostedFields.add(field + "^" + boost);

            boolean pinyinEnabled = Boolean.TRUE.equals(param.getPinyin())
                    && fc != null && Boolean.TRUE.equals(fc.getPinyin());
            if (pinyinEnabled) {
                boostedFields.add(field + ".pinyin^" + (boost * 0.6f));
                boostedFields.add(field + ".fpy^" + (boost * 0.6f));
            }
        }

        // 是否启用 fuzzy 由参数控制，且只在关键词不太长时开启：
        // 长句开启模糊匹配会让编辑距离展开的候选词数量爆炸，既慢又引入大量噪声。
        boolean useFuzzy = Boolean.TRUE.equals(param.getFuzzy()) && keyword.length() <= 16;

        return Query.of(q -> q.multiMatch(m -> {
            m.query(keyword)
                    .fields(boostedFields)
                    .type(TextQueryType.BestFields)
                    // AND 而非默认的 OR：OR 会让只命中一个字的文档也进入结果集，
                    // 在中文场景下噪声极大。代价是召回率降低，由 fuzzy 与同义词来补偿。
                    .operator(Operator.And);
            if (useFuzzy) {
                m.fuzziness("AUTO")
                        // 前缀长度：前 N 个字符必须精确匹配。这能大幅减少编辑距离展开的候选词，
                        // 是 fuzzy 查询性能的关键旋钮。
                        .prefixLength(properties.getSearch().getFuzzyPrefixLength())
                        .maxExpansions(properties.getSearch().getFuzzyMaxExpansions());
            }
            return m;
        }));
    }

    /**
     * 解析参与检索的字段。
     *
     * <p>用户可以通过 {@code searchFields} 指定，但指定的字段<b>必须</b>在类型配置里
     * 且 {@code searchable != false} —— 否则返回错误，而不是忽略后静默用默认字段。
     * 静默降级会让调用方以为自己的参数生效了，是排查成本很高的一类问题。
     */
    public List<String> resolveSearchFields(IndexDefinition def, SearchParam param) {
        if (param.getSearchFields() != null && !param.getSearchFields().isEmpty()) {
            for (String field : param.getSearchFields()) {
                FieldConfig fc = def.field(field)
                        .orElseThrow(() -> BizException.of(ResultCode.SEARCH_FIELD_NOT_ALLOWED,
                                "字段不在类型 " + def.getTypeCode() + " 的配置中: " + field));
                if (Boolean.FALSE.equals(fc.getSearchable())) {
                    throw BizException.of(ResultCode.SEARCH_FIELD_NOT_ALLOWED,
                            "字段不允许用于全文检索: " + field);
                }
            }
            return param.getSearchFields();
        }

        // 用类型配置的默认检索字段；没配就退化为「所有 searchable 字段」
        if (def.getDefaultSearchFields() != null && !def.getDefaultSearchFields().isEmpty()) {
            return def.getDefaultSearchFields();
        }
        if (def.getFields() == null) {
            return List.of();
        }
        return def.getFields().stream()
                .filter(f -> !Boolean.FALSE.equals(f.getSearchable()))
                .map(FieldConfig::getName)
                .toList();
    }

    // ==================================================================================
    //  过滤
    // ==================================================================================

    /**
     * 校验所有来自用户的字段名。
     *
     * <p>这是防「ES 查询注入」的核心：ES 没有 SQL 那样的注入语法，
     * 但允许用户传任意字段名去聚合或排序，可以让攻击者探测数据结构、
     * 或对未开启 doc_values 的字段发起操作把集群打挂。白名单是唯一可靠的防线。
     */
    private void validateFilters(IndexDefinition def, SearchParam param) {
        if (param.getFilters() != null) {
            if (param.getFilters().size() > properties.getSearch().getMaxFilterCount()) {
                throw BizException.of(ResultCode.SEARCH_QUERY_TOO_COMPLEX,
                        "过滤条件数量超过上限 " + properties.getSearch().getMaxFilterCount());
            }
            for (String field : param.getFilters().keySet()) {
                if (!def.hasField(field)) {
                    throw BizException.of(ResultCode.SEARCH_FIELD_NOT_ALLOWED,
                            "过滤字段不在类型 " + def.getTypeCode() + " 的配置中: " + field);
                }
            }
        }
        if (param.getRangeFilters() != null) {
            for (SearchParam.RangeFilter range : param.getRangeFilters()) {
                if (range.getField() == null || !def.hasField(range.getField())) {
                    throw BizException.of(ResultCode.SEARCH_FIELD_NOT_ALLOWED,
                            "范围过滤字段不合法: " + range.getField());
                }
            }
        }
    }

    /**
     * 单值用 term，多值用 terms 语义 —— 两者在 ES 里是不同查询，统一走 terms 更简单且行为一致。
     *
     * <p>字段名经 {@code exactField} 转换：精确过滤是 term 级操作，
     * 打在 TEXT 主字段上会命中分词后的单个 token 而不是原值，结果完全不是用户预期的
     * （例如按分类「手机数码」过滤会匹配到所有含「手机」的文档）。必须落到 .keyword 子字段。
     */
    private Query termQuery(IndexDefinition def, String field, List<String> values) {
        List<FieldValue> fieldValues = values.stream().map(FieldValue::of).toList();
        return Query.of(q -> q.terms(t -> t
                .field(def.exactField(field))
                .terms(ts -> ts.value(fieldValues))));
    }

    /**
     * 构造范围查询。
     *
     * <p><b>关于 {@code untyped} 变体</b>：ES Java Client 8.13 起把 {@code RangeQuery} 拆成了
     * {@code date} / {@code number} / {@code term} / {@code untyped} 四个变体（对应泛型参数
     * {@code DateTime} / {@code Double} / {@code String} / {@code JsonData}），不再有扁平的
     * {@code field()+gte()} 写法。这里统一用 {@code untyped}，理由是：
     * 本项目的检索参数是配置驱动的，边界值从 HTTP JSON 反序列化而来，运行期类型是 {@code Object}；
     * {@code untyped} 交给 ES 依据字段的 mapping 做类型劝导，比在应用层做一遍类型分发更可靠 ——
     * 应用层的分发必须与 mapping 保持同步，一旦类型配置改了而分发逻辑没改，就会静默地查出错误结果。
     */
    private Query rangeQuery(IndexDefinition def, SearchParam.RangeFilter range) {
        if (range.getGte() == null && range.getLte() == null
                && range.getGt() == null && range.getLt() == null) {
            return null;
        }
        return Query.of(q -> q.range(r -> r.untyped(u -> {
            u.field(range.getField());
            if (range.getGte() != null) {
                u.gte(JsonData.of(range.getGte()));
            }
            if (range.getLte() != null) {
                u.lte(JsonData.of(range.getLte()));
            }
            if (range.getGt() != null) {
                u.gt(JsonData.of(range.getGt()));
            }
            if (range.getLt() != null) {
                u.lt(JsonData.of(range.getLt()));
            }
            return u;
        })));
    }

    /** 供建议接口复用：按 bizId 精确取文档。 */
    public Query byIdQuery(IndexDefinition def, String bizId) {
        return termQuery(def, def.getPkField(), List.of(bizId));
    }

    /** 调试用：把查询渲染成 JSON，便于在 Kibana 里复现问题。 */
    public String explain(Query query) {
        return String.valueOf(query);
    }

    /** 暴露给上层做「是否有有效检索条件」的判断，用于决定是否记录搜索日志。 */
    public boolean hasSearchCondition(SearchParam param) {
        return (param.getKeyword() != null && !param.getKeyword().isBlank())
                || (param.getFilters() != null && !param.getFilters().isEmpty())
                || (param.getRangeFilters() != null && !param.getRangeFilters().isEmpty());
    }

    /** 供上层读取原始过滤条件做日志脱敏。 */
    public Map<String, List<String>> safeFilters(SearchParam param) {
        return param.getFilters() == null ? Map.of() : param.getFilters();
    }
}
