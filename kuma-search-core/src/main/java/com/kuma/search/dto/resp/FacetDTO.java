package com.kuma.search.dto.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 一个 facet 字段的聚合结果，对应前端筛选面板中的一组选项。
 */
@Data
@Builder
@Schema(description = "一个 facet 字段的聚合结果，对应前端筛选面板中的一组选项。")
public class FacetDTO implements Serializable {

    /** 字段名。 */
    @Schema(description = "字段名（ES 字段名）。", example = "categoryId")
    private String field;

    /** 字段展示名，取自类型配置的 label，避免前端硬编码字段名到中文的映射。 */
    @Schema(description = "字段展示名，取自类型配置的 label（为空时回退为字段名），避免前端硬编码「字段名 → 中文」的映射。", example = "分类")
    private String label;

    /** 该字段参与聚合的文档总数，用于渲染「全部」选项。 */
    @Schema(description = "该字段参与聚合的文档总数（各桶计数之和），用于渲染「全部」选项。", example = "128")
    private Long total;

    @Schema(description = "桶列表：每个桶是一个可选值及其文档数；数量上限由请求的 size/aggSize 与服务端 facet 配置共同决定。")
    private List<BucketDTO> buckets;

    @Data
    @Builder
    @Schema(description = "facet 的一个取值桶。")
    public static class BucketDTO implements Serializable {

        @Schema(description = "桶键，即字段的取值。", example = "1001")
        private String key;

        @Schema(description = "该取值命中的文档数。注意字段名是 docCount，不是 count。", example = "80")
        private Long docCount;
    }
}
