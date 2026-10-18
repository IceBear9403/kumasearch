package com.kuma.search.dto.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 网页采集检索的响应体，对应 {@code POST /api/search/web} 的 {@code data} 字段。
 *
 * <p>与 {@link SearchResponseDTO} 是<b>两个独立接口的两种响应</b>，不是一个接口的两种形态。
 * 前端并发请求两个接口、分区渲染（见 {@code docs/02-architecture.md} 与
 * {@code docs/01-requirements.md} 的分组展示决策），这样任一侧失败都能独立降级 ——
 * 自有结果先渲染出来，网页区块显示「加载失败，点击重试」，
 * 而不是整个搜索结果页因为其中一个超时而一片空白。
 *
 * <p>因此这里<b>刻意不包含</b> {@code correctedKeyword}（拼写纠错只对自有内容做，
 * 网页侧没有可用的词表）与自由 facet（只提供站点维度的聚合）。
 */
@Data
@Builder
@Schema(description = "网页采集检索响应体，对应 POST /api/search/web 的 data 字段。与站内检索是两个独立接口的响应，不是一个接口的两种形态。")
public class WebSearchResponseDTO implements Serializable {

    /** 命中列表。为空数组表示没有结果，不是 null。 */
    @Schema(description = "命中列表。为空数组表示没有结果，不是 null。")
    private List<WebHitDTO> hits;

    /** 总命中数。走游标翻页时为 null。 */
    @Schema(description = "总命中数。游标翻页（cursorMode=true）时为 null。", example = "342")
    private Long total;

    @Schema(description = "当前页码，从 1 开始；游标翻页时为 null。", example = "1")
    private Integer pageNum;

    @Schema(description = "每页条数。", example = "10")
    private Integer pageSize;

    @Schema(description = "总页数；total 为 null（游标翻页）时为 null。", example = "35")
    private Integer pages;

    /** 下一页游标。为 null 表示已到末页。 */
    @Schema(description = "下一页游标。为 null 表示已到末页；非 null 时原样回填到下一次请求的 cursor 字段继续翻页。")
    private String nextCursor;

    /** 本次查询是否走了游标翻页。 */
    @Schema(description = "本次查询是否走了游标翻页。为 true 时 total/pageNum/pages 均为 null 且不支持跳页，前端分页组件应切换为「上一页 / 下一页」形态。", example = "false")
    private Boolean cursorMode;

    /**
     * 来源站点聚合，用于渲染「站点分布」区块（哪个站的结果最多）。
     *
     * <p>复用 {@link FacetDTO.BucketDTO} 的形态而不是自定义一个
     * {@code DomainCount}：字段完全一样（key + docCount），
     * 前端能直接复用同一个渲染组件，而多定义一个仅名字不同的类型只会让
     * 「这两个东西是不是一回事」变成一个新的疑问。
     */
    @Schema(description = "来源站点聚合，用于渲染「站点分布」区块（哪个站的结果最多）。"
            + "复用 FacetDTO.BucketDTO 的形态：key 为域名、docCount 为该站命中数；只提供站点这一个维度。")
    private List<FacetDTO.BucketDTO> domainFacets;

    /** 服务端耗时（毫秒）。 */
    @Schema(description = "服务端执行耗时（毫秒），取自本次 ES 查询耗时。", example = "41")
    private Long tookMillis;

    /** 是否只返回了部分结果（ES 超时或部分分片失败）。为 true 时前端应提示结果可能不完整。 */
    @Schema(description = """
            结果是否不完整、不可信，含义与站内检索完全一致。true 表示这次查询没能给出可信的完整结果：
            ES 超时 / 部分分片失败，或该类型的索引尚未创建（后端降级成「空结果 + partial=true」）。
            ⚠️ partial=true 且 hits 为空时不能当作「没有相关内容」，前端必须更换空状态文案；
            采集类型最常见的触发场景就是 webpage 索引还没建。""",
            example = "false")
    private Boolean partial;
}
