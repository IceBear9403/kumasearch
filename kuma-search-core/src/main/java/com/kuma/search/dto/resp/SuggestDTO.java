package com.kuma.search.dto.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 一条搜索建议，对应输入框下拉列表中的一行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "一条搜索建议，对应输入框下拉列表中的一行。")
public class SuggestDTO implements Serializable {

    /** 建议词文本。 */
    @Schema(description = "建议词文本。", example = "手机壳")
    private String text;

    /** 权重，来自 completion 字段的 weight。 */
    @Schema(description = "权重。索引建议来自 completion 字段的 weight；个人历史与热词来源无此概念，统一为 0。"
            + "列表顺序按来源优先级（history > hot > index）合并，不依赖该值排序。",
            example = "100")
    private Integer weight;

    /**
     * 建议类型，用于前端加不同前缀图标。
     * 取值：{@code hot}（来自热词榜）、{@code index}（来自索引建议）、{@code history}（来自个人历史）。
     */
    @Schema(description = "建议类型，用于前端加不同前缀图标。取值：hot（来自热词榜）、index（来自索引建议）、"
            + "history（来自个人历史）；未登录时不会有 history。",
            example = "index")
    private String source;
}
