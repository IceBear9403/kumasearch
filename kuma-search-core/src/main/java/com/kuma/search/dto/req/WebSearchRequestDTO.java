package com.kuma.search.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 网页采集检索请求，对应 {@code POST /api/search/web}。
 *
 * <h3>⚠️ 这个 DTO 的字段是刻意「少」的，不要照着 {@link SearchRequestDTO} 补齐</h3>
 * 它<b>没有</b> {@code filters} / {@code rangeFilters} / {@code searchFields} /
 * {@code highlightFields} / {@code facets} 这些字段，这是安全设计而不是功能取舍：
 *
 * <p>网页采集的内容里混着<b>未审核</b>（{@code status=0}）与<b>已驳回</b>（{@code status=2}）
 * 的记录，它们也在 ES 里。服务端必须在查询里强制注入 {@code status=1} 过滤，
 * 否则未审核的内容会直接对外可见 —— 人工审核队列就白做了。
 *
 * <p>而「强制注入」只有在<b>调用方无法自己塞 filters</b> 时才成立。
 * 一旦这里暴露了 {@code filters}，调用方就能传一个覆盖掉 {@code status} 的条件，
 * 那不是「绕过了过滤」，而是「过滤器本身可以被参数关掉」。
 *
 * <p>因此{@code typeCode} 也不暴露：它固定为 {@code webpage}，
 * 传别的值等于让这个接口变成任意类型的检索入口。
 *
 * <h3>为什么没有时间筛选与时间排序</h3>
 * 采集内容的 {@code publishTime} 目前刻意留空（原因见 {@code CrawlPipeline#persist}：
 * ES 的 date 格式是否接受带 {@code T} 的值尚无实测结论，
 * 而失败的粒度是整条文档）。<b>在一个全部为 null 的字段上做范围过滤，
 * 结果恒为空集</b> —— 与其提供一个「用了就没结果」的筛选项，不如先不提供。
 * 时间维度与 {@code publishTime} 一起在 {@code docs/08-roadmap.md} §6.1 的第 1 项落地。
 */
@Data
@Schema(description = "网页采集检索请求体，对应 POST /api/search/web")
public class WebSearchRequestDTO implements Serializable {

    /** 检索关键词。 */
    @Schema(description = "必填。搜索关键词，不接受空值 —— 「列出全部」是站点地图的职责，不是检索接口的职责。", example = "手机 评测")
    @NotBlank(message = "关键词不能为空")
    @Size(max = 200, message = "关键词长度不能超过 200")
    private String keyword;

    /**
     * 限定来源站点（域名），如 {@code ["example.com"]}。
     *
     * <p>为空表示不限。这些值会被合并进服务端构造的过滤条件，
     * 与 {@code status=1} 是<b>并列</b>关系（都是 filter，AND 语义），
     * 因此传什么都无法把未审核的内容筛出来。
     */
    @Schema(description = "限定来源站点（域名）白名单，最多 10 个，多个之间是「或」关系；为空表示不限。"
            + "它在服务端与强制的 status=1 过滤是并列的 AND 关系，传什么都无法把未审核内容筛出来。",
            example = "[\"example.com\"]")
    @Size(max = 10, message = "最多同时筛选 10 个站点")
    private List<String> domains;

    @Schema(description = "页码，从 1 开始，默认 1。", example = "1")
    @Min(value = 1, message = "页码从 1 开始")
    private Integer pageNum = 1;

    @Schema(description = "每页条数，默认 10，上限 50。", example = "10")
    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 50, message = "每页最多 50 条")
    private Integer pageSize = 10;
}
