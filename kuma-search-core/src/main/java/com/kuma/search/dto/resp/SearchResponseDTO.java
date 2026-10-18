package com.kuma.search.dto.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 检索接口的响应体，对应 {@code POST /api/search} 的 {@code data} 字段。
 */
@Data
@Builder
@Schema(description = "站内检索响应体，对应 POST /api/search 的 data 字段。")
public class SearchResponseDTO implements Serializable {

    /** 命中列表。 */
    @Schema(description = "命中列表。为空数组表示没有结果，不是 null。")
    private List<HitDTO> hits;

    /** 总命中数。走游标翻页时为 null —— 此时前端应改用 {@code nextCursor} 判断是否还有下一页。 */
    @Schema(description = "总命中数。游标翻页（cursorMode=true）时为 null，此时前端应改用 nextCursor 判断是否还有下一页；"
            + "trackTotalHits=false 时为估算下界（可能偏小）而非精确值。",
            example = "128")
    private Long total;

    @Schema(description = "当前页码，从 1 开始；游标翻页时为 null。", example = "1")
    private Integer pageNum;

    @Schema(description = "每页条数。", example = "10")
    private Integer pageSize;

    @Schema(description = "总页数；total 为 null（游标翻页）时为 null。", example = "13")
    private Integer pages;

    /** 下一页游标。为 null 表示已到末页。 */
    @Schema(description = "下一页游标。为 null 表示已到末页；非 null 时原样回填到下一次请求的 cursor 字段继续翻页。")
    private String nextCursor;

    /** 本次查询是否走了游标翻页，前端据此切换分页组件的形态。 */
    @Schema(description = "本次查询是否走了游标翻页（search_after）。为 true 时 total/pageNum/pages 均为 null 且不支持跳页，"
            + "前端分页组件应切换为「上一页 / 下一页」形态，不要渲染页码；与页码分页互斥。",
            example = "false")
    private Boolean cursorMode;

    /** facet 聚合结果，用于渲染左侧筛选面板。 */
    @Schema(description = "facet 聚合结果，用于渲染左侧筛选面板；数组顺序与请求中 facets 的顺序一致，没有聚合时为空数组。")
    private List<FacetDTO> facets;

    /**
     * 服务端执行耗时（毫秒）。包含 ES 查询与结果处理，
     * 前端可展示「找到约 N 条结果，耗时 X 毫秒」，这也是排查性能问题的第一手信息。
     */
    @Schema(description = "服务端执行耗时（毫秒），取自本次 ES 查询耗时。前端可展示「找到约 N 条结果，耗时 X 毫秒」，"
            + "也是排查性能问题的第一手信息。",
            example = "23")
    private Long tookMillis;

    /** 是否只返回了部分结果（ES 超时或部分分片失败）。为 true 时前端应提示结果可能不完整。 */
    @Schema(description = """
            结果是否不完整、不可信。true 表示这次查询没能给出可信的完整结果，有两种来源：
            ① ES 超时或部分分片失败（文档里还有本次没返回的）；
            ② 该类型的索引尚未创建 —— 后端把 index_not_found_exception 降级成「空结果 + partial=true」而不是报错。
            ⚠️ 因此当 partial=true 且 hits 为空时，不能据此断定「没有相关内容」：实际是「这次没查成」。
            本字段是区分「确实没有结果」与「这类内容暂时查不了」的唯一信号，前端必须据此更换空状态文案
            （例如「该类型暂时无法检索」），否则会把「没查成」显示成「库里没有」。""",
            example = "false")
    private Boolean partial;

    /** 当原关键词无结果、但拼写纠错后能命中时，这里给出建议的修正词。 */
    @Schema(description = "兜底建议词。当关键词无结果、且前缀联想能给出一个不同的建议词时填入，供前端展示「你是不是想搜 X」。"
            + "返回 null 表示没有可给的建议，前端此时应展示「没有找到相关内容 + 筛选建议」，不要硬塞无关的词。"
            + "注意它是前缀联想兜底，不是真正的拼写纠错。",
            example = "手机")
    private String correctedKeyword;
}
