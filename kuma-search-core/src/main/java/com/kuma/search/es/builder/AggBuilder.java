package com.kuma.search.es.builder;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.es.model.SearchParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 构造 facet 聚合子句。
 *
 * <h3>为什么是 terms 聚合而不是 global 聚合</h3>
 * 本项目的 facet 是「筛选面板」语义：用户勾选了「品牌 = 华为」之后，
 * 面板里其他品牌的计数应该<b>基于已过滤的结果集</b>变化，而不是永远显示全库计数。
 * 这正是 ES 默认行为（聚合作用在查询结果上），因此直接用 terms 即可。
 * 如果要做「同时展示全库计数与当前结果计数」，才需要额外挂 global 子聚合 —— 那是另一种需求。
 *
 * <h3>关于内存</h3>
 * terms 聚合需要在协调节点上为每个分片返回的桶做归并，桶数量随 {@code size} 线性增长。
 * 把 size 设成几百会显著增加堆压力，因此这里有硬上限。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AggBuilder {

    private final KumaSearchProperties properties;

    /**
     * 构造聚合。
     *
     * @return 聚合名 -> 聚合定义。聚合名直接使用字段名，便于响应解析时一一对应
     */
    public Map<String, Aggregation> build(IndexDefinition def, SearchParam param) {
        Map<String, Aggregation> aggregations = new LinkedHashMap<>();

        if (param.getFacets() == null || param.getFacets().isEmpty()) {
            return aggregations;
        }

        if (param.getFacets().size() > properties.getSearch().getMaxFacetCount()) {
            throw BizException.of(ResultCode.SEARCH_QUERY_TOO_COMPLEX,
                    "聚合字段数量超过上限 " + properties.getSearch().getMaxFacetCount());
        }

        for (SearchParam.FacetSpec spec : param.getFacets()) {
            String field = spec.getField();
            if (field == null || field.isBlank()) {
                continue;
            }
            if (!def.hasField(field)) {
                throw BizException.of(ResultCode.SEARCH_FIELD_NOT_ALLOWED,
                        "聚合字段不在类型 " + def.getTypeCode() + " 的配置中: " + field);
            }
            if (!def.isFacetable(field)) {
                throw BizException.of(ResultCode.SEARCH_FIELD_NOT_ALLOWED,
                        "字段未开启聚合能力（facetable=false）: " + field);
            }

            int size = spec.getSize() != null && spec.getSize() > 0
                    ? Math.min(spec.getSize(), properties.getSearch().getFacetSize() * 5)
                    : properties.getSearch().getFacetSize();

            // 聚合名仍用逻辑字段名（响应解析时按它对应回来），
            // 但真正打给 ES 的字段名要经过 exactField 转换 —— TEXT 字段必须落到 .keyword 子字段上。
            aggregations.put(field, Aggregation.of(a -> a.terms(t -> t
                    .field(def.exactField(field))
                    .size(size))));
        }

        return aggregations;
    }

    /** 是否有聚合需要执行，供上层决定是否解析聚合响应。 */
    public boolean hasAggregations(SearchParam param) {
        return param.getFacets() != null && !param.getFacets().isEmpty();
    }
}
