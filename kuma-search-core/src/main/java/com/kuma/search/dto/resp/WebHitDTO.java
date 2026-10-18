package com.kuma.search.dto.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 网页采集检索的单条结果。
 *
 * <p>与 {@link HitDTO} 分开的两个理由，都不是「换个名字更清楚」：
 * <ol>
 *   <li><b>字段语义不同</b> —— 网页结果必须带上 {@code sourceUrl}（详情跳原站）
 *       与 {@code domain}（来源站点），而自有内容的详情在站内，没有这两个概念。</li>
 *   <li><b>暴露面必须收紧</b> —— {@link HitDTO} 的 {@code fields} 是一个
 *       「字段名 → 任意值」的开放 Map，它会把 ES 文档里所有声明过的字段一并送到前端。
 *       网页内容是<b>第三方数据</b>，多送出去的每一个字段都是一次不必要的信息暴露
 *       （例如 {@code status}、{@code bizWeight} 这类内部字段泄不泄露都无所谓，
 *       但「多送」这个习惯本身会在下一次加字段时变成真问题）。
 *       这里只列前端确实要渲染的项。</li>
 * </ol>
 */
@Data
@Builder
@Schema(description = "网页采集检索的单条结果。与 HitDTO 是两个类：字段语义不同（多出 sourceUrl/domain），"
        + "且暴露面刻意收紧（不返回开放的 fields Map，只列前端确实要渲染的项）。")
public class WebHitDTO implements Serializable {

    /** 文档 ID，等于 {@code url_hash}。仅用于前端做列表 key，不用于跳转。 */
    @Schema(description = "文档 ID，等于采集内容的 url_hash（URL 归一化后的 MD5）。仅用于前端列表 :key 与详情定位，"
            + "不是业务 ID，不要展示、也不要用于跳转。",
            example = "9f2c1d8e4b6a5f3e2d1c0b9a8f7e6d5c")
    private String id;

    /** 页面标题。取自原站，可能为空（少数页面没有 title，抽取阶段已尽量拦掉）。 */
    @Schema(description = "页面标题。取自原站，可能为空（少数页面没有 title，抽取阶段已尽量拦掉）。")
    private String title;

    /**
     * 原文链接，前端「查看原文」与标题点击的目标。
     *
     * <p>⚠️ 前端<b>必须</b>把它当作外部链接处理：加 {@code rel="noopener noreferrer"} 与
     * {@code target="_blank"}。它来自第三方页面，虽然经过 URL 归一化与范围校验
     * （只可能是 http/https），但把它渲染成站内跳转会在点击时把用户留在本站的
     * 路由里，行为上说不通、体验上也奇怪。
     */
    @Schema(description = """
            原文链接，「查看原文」与标题点击的目标。
            ⚠️ 前端必须把它当作外部链接处理：加 rel="noopener noreferrer" 与 target="_blank"。
            它来自第三方页面（虽经 URL 归一化与范围校验，只可能是 http/https），
            但渲染成站内跳转会在点击时把用户留在本站的路由里，行为与体验都不对。""",
            example = "https://example-news.com/p/12345")
    private String sourceUrl;

    /** 来源域名。用于分组展示与「只看这个站」的快捷筛选。 */
    @Schema(description = "来源域名。用于分组展示与「只看这个站」的快捷筛选。", example = "example-news.com")
    private String domain;

    /** 摘要（来自原站的 meta description，或正文头部截断）。未高亮时的展示内容。 */
    @Schema(description = "页面摘要。这是采集时抽取的摘要（原站 meta description 或正文头部截断），不是全文镜像；"
            + "未高亮时作为展示内容。")
    private String summary;

    /**
     * 高亮片段：字段名 → 带 {@code <em>} 标记的片段列表。
     *
     * <p>⚠️ <b>安全提示</b>：片段里是第三方页面的原文，是彻头彻尾的不可信数据。
     * 前端<b>必须</b>用 {@code HighlightText} 这类「先转义再插入标记」的组件渲染，
     * <b>绝对不能用 {@code v-html}</b> —— 采集来的页面里带 {@code <script>} 是常态而非例外。
     */
    @Schema(description = """
            高亮片段：字段名 → 带 <em> 标记的片段列表。
            ⚠️ 安全提示：片段是第三方页面的原文，属于彻头彻尾的不可信数据。
            前端必须用 HighlightText 这类「先转义再插入标记」的组件渲染，绝对不能用 v-html ——
            采集来的页面里带 <script> 是常态而非例外。""")
    private Map<String, List<String>> highlights;

    /** 相关度得分。前端不展示，用于联调时判断排序是否符合预期。 */
    @Schema(description = "相关度得分。前端不展示，用于联调时判断排序是否符合预期。", example = "8.71")
    private Double score;
}
