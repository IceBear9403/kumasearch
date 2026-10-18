package com.kuma.search.es.mapping;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 单个字段的索引配置。对应 {@code t_index_type_config.field_config} JSON 数组里的一个元素。
 *
 * <p>这个类的字段集合 = 「管理员能在后台上配什么」。有意保持精简：
 * 凡是可以放到<b>查询期</b>决定的（比如字段权重 boost），就不放进索引期配置。
 * 原因是索引期 boost 一旦写进 mapping 就不可改，要调整得重建索引；
 * 而查询期的 {@code multi_match.fields} 权重可以随时热调，还能配合 A/B 实验。
 */
@Data
public class FieldConfig implements Serializable {

    /** 字段名，ES 侧与 MySQL {@code t_search_content} 的列名或 {@code extra} 里的 key 对应。 */
    private String name;

    /** 展示名，用于后台配置界面与 facet 面板标题。 */
    private String label;

    /** 字段类型。 */
    private FieldType type = FieldType.TEXT;

    /** 索引期分词器。为空时 TEXT 用全局默认 ik_max_word，其余类型忽略该配置。 */
    private String analyzer;

    /** 查询期分词器。为空时 TEXT 用全局默认 kuma_search_analyzer（含同义词）。 */
    private String searchAnalyzer;

    /**
     * 查询期权重。参与 multi_match 的字段加权，值越大越优先命中。
     * 典型配置：title=3.0、summary=1.5、content=1.0。
     */
    private Float boost = 1.0f;

    /** 是否参与默认全文检索。false 的字段只能通过显式 filters / sorts 使用。 */
    private Boolean searchable = true;

    /** 是否可在 facet 面板中作为筛选项聚合。 */
    private Boolean facetable = false;

    /** 是否可作为排序字段。 */
    private Boolean sortable = false;

    /** 是否参与关键词高亮。 */
    private Boolean highlight = false;

    /** 是否为该字段生成拼音子字段（全拼 + 首字母）。仅 TEXT / KEYWORD 有意义。 */
    private Boolean pinyin = false;

    /** 是否为该字段生成补全建议子字段（completion）。会写入独立的 suggest 索引，不占主索引堆。 */
    private Boolean suggest = false;

    /** 建议词权重，用于 completion suggester 的排序。 */
    private Integer suggestWeight = 0;

    /** 是否为多值字段（数组）。 */
    private Boolean multiValued = false;

    /** DATE 类型的格式串，为空时使用全局默认格式。 */
    private String format;

    /** custom analyzer 场景下的补充过滤链，一般不用填。 */
    private List<String> filters;
}
