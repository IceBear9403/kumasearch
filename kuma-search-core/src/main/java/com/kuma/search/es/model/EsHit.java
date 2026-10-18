package com.kuma.search.es.model;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 单条搜索命中结果，屏蔽掉 ES 的原始响应结构。
 *
 * <p>刻意不直接把 ES 的 {@code Hit<T>} 透传到接口层，原因有二：
 * 一是前端不该感知 ES 的内部字段（{@code _score}、{@code _index} 等）；
 * 二是后续若引入向量检索或多路召回融合，语义可在这一层统一。
 */
@Data
@Builder
public class EsHit implements Serializable {

    /** 文档主键，等于业务表里 biz_id 的值。 */
    private String id;

    /** 相关度得分。未开启打分（如纯 filter 查询）时为 null。 */
    private Double score;

    /** 文档原始字段。key 为 ES 字段名。 */
    private Map<String, Object> source;

    /** 命中的高亮片段：字段名 -> 已带 {@code <em>} 标记的片段列表。 */
    private Map<String, List<String>> highlights;

    /** 由 {@code source} 中若干字段拼出的上下文摘要，用于结果列表展示。 */
    private String summary;
}
