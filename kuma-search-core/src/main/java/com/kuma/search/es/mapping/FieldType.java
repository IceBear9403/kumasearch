package com.kuma.search.es.mapping;

import lombok.Getter;

/**
 * 配置表 {@code t_index_type_config.field_config} 中允许声明的字段类型。
 *
 * <p>这是一层<b>受控的</b>类型白名单，而不是把 ES 的全部字段类型开放给配置：
 * 配置能表达的越多，MappingGenerator 的分支和运维的心智负担就越重。
 * 需要新增类型时在这里加枚举 + 在 {@code MappingGenerator} 里加一个分支，
 * 依然是「改一处、所有类型共享」。
 */
@Getter
public enum FieldType {

    /** 全文检索字段。索引期 ik_max_word 细粒度切分，查询期 ik_smart 粗粒度，自动带 pinyin 子字段。 */
    TEXT("text"),

    /** 精确值字段。用于过滤、聚合、排序，不分词。 */
    KEYWORD("keyword"),

    INTEGER("integer"),
    LONG("long"),
    DOUBLE("double"),
    BOOLEAN("boolean"),

    /** 日期。默认按 {@code yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||epoch_millis} 解析。 */
    DATE("date"),

    /** 嵌套对象。需要独立检索内部字段时使用，注意 nested 查询不能与普通查询混用。 */
    NESTED("nested"),

    GEO_POINT("geo_point");

    private final String esType;

    FieldType(String esType) {
        this.esType = esType;
    }

    /** 是否可用于 facet 聚合与排序（取决于是否为 doc_values 类型）。 */
    public boolean supportsDocValues() {
        return this != TEXT && this != NESTED;
    }

    public static FieldType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return KEYWORD;
        }
        for (FieldType t : values()) {
            if (t.name().equalsIgnoreCase(code.trim()) || t.esType.equalsIgnoreCase(code.trim())) {
                return t;
            }
        }
        throw new IllegalArgumentException("不支持的字段类型: " + code);
    }
}
