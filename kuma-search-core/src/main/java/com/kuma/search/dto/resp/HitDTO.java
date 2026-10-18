package com.kuma.search.dto.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 单条命中结果的对外形态。
 */
@Data
@Builder
@Schema(description = "站内检索的单条命中结果。")
public class HitDTO implements Serializable {

    /** 文档主键，等于业务表的 bizId，前端据此跳转到详情页。 */
    @Schema(description = "文档主键，等于业务表的 bizId（ES 文档 _id 由「类型 + 该值」派生），前端据此跳转到详情页。", example = "art-10001")
    private String id;

    /** 相关度得分。前端一般不展示，但调试与 A/B 对比时需要。 */
    @Schema(description = "相关度得分。前端一般不展示，但调试与 A/B 对比时需要。", example = "12.34")
    private Double score;

    /** 文档字段。key 为 ES 字段名（已按类型配置裁剪，不含内部字段）。 */
    @Schema(description = "文档字段。key 为 ES 字段名（已按类型配置裁剪，不含内部字段），取值可能是单值也可能是数组。"
            + "用于渲染结果卡片的常规字段（标题、分类、价格等）。")
    private Map<String, Object> fields;

    /**
     * 高亮片段：字段名 -> 带 {@code <em>} 标记的片段列表。
     *
     * <p>安全提示：这些片段来自 ES 的 highlight 结果，内容是<b>用户数据</b>。
     * 前端渲染时<b>必须</b>用 v-html 之外的方式（或先做转义），
     * 否则文档内容里的 HTML 会变成 XSS 载体。详见 {@code docs/06-security.md}。
     */
    @Schema(description = "高亮片段：字段名 → 带 <em> 标记的片段列表。"
            + "⚠️ 安全提示：片段来自 ES highlight，内容是用户数据。前端渲染必须先转义（或用 HighlightText 这类组件），"
            + "绝不能用 v-html 直接插入，否则文档内容里的 HTML 会变成 XSS 载体。详见 docs/06-security.md。")
    private Map<String, List<String>> highlights;

    /** 服务端生成的上下文摘要（未高亮时使用）。 */
    @Schema(description = "服务端生成的上下文摘要（未高亮时使用）。优先取高亮片段，其次按类型配置的摘要字段截断，是纯文本，可直接插值展示。")
    private String summary;
}
