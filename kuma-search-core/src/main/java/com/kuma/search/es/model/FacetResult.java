package com.kuma.search.es.model;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 一个 facet 字段的聚合结果。
 *
 * <p>注意 {@code buckets} 只包含 ES 返回的 top N 个桶（N 由
 * {@code SearchParam#aggSize} 决定），不是全量取值 —— 这对「筛选项展示」够用，
 * 但如果有「展示全部可选值」的需求，应改为独立的字段字典接口而非放大 aggSize。
 */
@Data
@Builder
public class FacetResult implements Serializable {

    /** facet 字段名。 */
    private String field;

    /** 参与了该字段聚合的文档总数（含未被选中过滤条件命中的），用于前端展示「全部」项。 */
    private Long total;

    /** 桶列表，按 docCount 降序。 */
    private List<Bucket> buckets;

    /**
     * 单个聚合桶。
     *
     * @param key      桶的取值，已转为字符串便于前端直接渲染
     * @param docCount 该取值命中的文档数
     */
    @Data
    @Builder
    public static class Bucket implements Serializable {
        private String key;
        private Long docCount;
    }
}
