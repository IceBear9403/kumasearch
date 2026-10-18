package com.kuma.search.es.model;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 通用搜索参数 —— 整个检索链路的统一入参。
 *
 * <p>设计要点：把「检索、过滤、聚合、排序、高亮、翻页、增强开关」全部收进一个对象，
 * 使得 {@code QueryBuilder} 的输入只有一个，未来新增能力（如向量检索）只需扩展本类字段，
 * 而不必改调用方签名。
 *
 * <p><b>安全约束</b>：{@code filters} / {@code facets} / {@code sorts} / {@code highlightFields}
 * 里的字段名 <b>必须</b> 经过 {@code IndexDefinition} 的白名单校验后才能进入 DSL，
 * 否则用户可以传任意字段名做聚合或排序 —— 这是 ES 场景下最典型的注入风险。
 * 校验入口见 {@code SearchServiceImpl#validateAgainstDefinition}。
 *
 * <p><b>翻页语义</b>：{@code useCursor = false} 走 from/size 浅分页，会返回 total；
 * 超过 {@code kuma.search.max-result-window} 或显式 {@code useCursor = true} 时走
 * search_after + PIT 游标翻页，此时不返回 total，且无法跳页。
 */
@Data
@Builder
public class SearchParam implements Serializable {

    /** 索引类型编码，对应 config 表中的 type_code，如 article / product。必填。 */
    private String typeCode;

    /** 用户输入的关键词。为空时退化为纯过滤 + 聚合查询（用于分类页等场景）。 */
    private String keyword;

    /** 指定检索的字段。为 null 时使用类型配置里的默认检索字段与其 boost 权重。 */
    private List<String> searchFields;

    /** 精确过滤（term/terms）：字段名 -> 允许值列表。多字段之间是 AND，同字段多值是 OR。 */
    private Map<String, List<String>> filters;

    /** 范围过滤（价格、时间区间等）。 */
    private List<RangeFilter> rangeFilters;

    /** 需要做 facet 聚合的字段。 */
    private List<FacetSpec> facets;

    /** 排序规则。为空时使用类型配置里的默认排序（通常是相关度 + 业务权重）。 */
    private List<SortSpec> sorts;

    /** 需要高亮的字段。为空时使用类型配置里的默认高亮字段。 */
    private List<String> highlightFields;

    /** 页码，从 1 开始。仅浅分页模式有效。 */
    @Builder.Default
    private Integer pageNum = 1;

    /** 每页条数，上限见 {@code kuma.search.max-page-size}。 */
    @Builder.Default
    private Integer pageSize = 10;

    /** search_after 游标（由上一页返回的 nextCursor 原样带回）。 */
    private String cursor;

    /** 是否强制走游标翻页。false 时由服务端根据 pageNum*pageSize 是否越过窗口自动判定。 */
    @Builder.Default
    private Boolean useCursor = false;

    /** 最低相关度分数，低于该分数的结果直接丢弃，用于抑制弱相关的长尾噪声。 */
    private Float minScore;

    /** 是否启用模糊匹配（拼写纠错）。 */
    @Builder.Default
    private Boolean fuzzy = true;

    /** 是否启用同义词扩展。 */
    @Builder.Default
    private Boolean synonym = true;

    /** 是否启用拼音（全拼 + 首字母）检索。 */
    @Builder.Default
    private Boolean pinyin = true;

    /** 每个 facet 字段返回的桶数量上限。设过大会显著增加协调节点的内存压力。 */
    @Builder.Default
    private Integer aggSize = 10;

    /** 是否请求精确 total。关闭后 ES 跳过计数，深分页场景下能明显降低开销。 */
    @Builder.Default
    private Boolean trackTotalHits = true;

    /** 请求来源标识（web / api / admin），用于搜索日志归因。 */
    private String source;

    /**
     * 范围过滤条件。
     *
     * @param field 字段名，需通过白名单校验
     * @param gte   大于等于，字符串形式由 ES 自行按字段类型解析
     * @param lte   小于等于
     * @param gt    大于
     * @param lt    小于
     */
    @Data
    @Builder
    public static class RangeFilter implements Serializable {
        private String field;
        private Object gte;
        private Object lte;
        private Object gt;
        private Object lt;
    }

    /**
     * 聚合（facet）声明。
     *
     * @param field facet 字段，需为 keyword 类型或已开启 fielddata
     * @param size  该字段返回的桶数量，为空时使用 {@code SearchParam#aggSize}
     */
    @Data
    @Builder
    public static class FacetSpec implements Serializable {
        private String field;
        private Integer size;
    }

    /**
     * 排序声明。
     *
     * @param field 排序字段
     * @param order asc / desc
     * @param mode  多值字段的排序模式：min / max / avg / sum，为空时用 ES 默认
     */
    @Data
    @Builder
    public static class SortSpec implements Serializable {
        private String field;
        private String order;
        private String mode;
    }
}
