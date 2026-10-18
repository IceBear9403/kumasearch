package com.kuma.search.dto.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量审核的结果，对应 {@code POST /api/admin/crawl/audit/batch} 的 {@code data} 字段。
 *
 * <h3>为什么不是一个「成功/失败」的布尔值</h3>
 * 批量操作与单条操作的本质差别是：<b>它一定会部分成功</b>。
 * 一批 200 条里有一条因为并发被别人改过状态、有三条已经不存在，
 * 这不是「操作失败」，而是「做了大部分，这几条需要你再看一眼」。
 * 把它压成一个布尔值，就等于让调用方在这两种完全不同的处境下做同一个动作
 * （要么当作全成功而漏掉那几条，要么当作全失败而把 197 条重做一遍）。
 *
 * <p>因此这里给的是<b>可对账的明细</b>：成功多少、失败多少、跳过了多少、分别是谁、为什么。
 * 四个计数满足 {@code requested = succeeded + failed + skipped}，
 * 这条等式是调用方唯一需要知道的契约 —— 对不上账就说明本接口有 bug。
 *
 * <h3>skipped 与 failed 的区别不能混</h3>
 * 两者的处置方式完全不同：
 * <ul>
 *   <li>{@code failed}（失败）= 本可以处理但出错了，<b>需要重试或排查</b>；</li>
 *   <li>{@code skipped}（跳过）= 状态已经是要处理成的样子，或这条内容压根不属于采集内容。
 *       <b>重试没有意义，也不该报错</b>。典型来源是"两个人同时在看同一批待审"，
 *       或者按 id 勾选时前端拿到的列表已经过期。</li>
 * </ul>
 */
@Data
@Builder
@Schema(description = "批量审核结果。四个计数满足 requested = succeeded + failed + skipped，"
        + "这条等式是调用方唯一需要知道的契约。failed 与 skipped 的处置方式不同：后者重试没有意义。")
public class BatchAuditResultDTO implements Serializable {

    /**
     * 本次实际纳入处理的条数。
     *
     * <p>按 id 模式 = 请求里给的 id 个数（去重后）；按条件模式 = 命中筛选条件的待审条数，
     * 最多 {@code limit} 条。注意它<b>不等于</b>"系统里符合条件的总数" ——
     * 后者要看 {@link #remainingPending}。
     */
    @Schema(description = "本次实际纳入处理的条数。按 id 模式=去重后的 id 个数；按条件模式=命中的待审条数（最多 limit）。"
            + "不等于「系统里符合条件的总数」，后者看 remainingPending。", example = "200")
    private Integer requested;

    /** 处理成功（状态已改、审核日志已落、Outbox 事件已写）的条数。 */
    @Schema(description = "处理成功的条数：状态已改、审核日志已落、Outbox 事件已写。", example = "197")
    private Integer succeeded;

    /** 处理失败、需要重试或排查的条数。明细见 {@link #failures}。 */
    @Schema(description = "处理失败的条数，例如并发修改导致的冲突、内容行已被删除。明细见 failures。", example = "1")
    private Integer failed;

    /** 被跳过、未做任何改动的条数。重试没有意义，也不应视为错误。 */
    @Schema(description = "被跳过的条数：状态已经是目标状态、或该内容不属于采集内容（type_code 不是 webpage）。"
            + "跳过不是错误，重试没有意义。", example = "2")
    private Integer skipped;

    /**
     * 失败明细。
     *
     * <p>有上限（{@code MAX_FAILURE_DETAILS}）：一次批量操作如果有几百条同时失败，
     * 真正的原因几乎总是同一个（例如数据库挂了），逐条返回几百条记录只会让响应体膨胀，
     * 而排查需要的只是"前几条长什么样"。超出的部分只计数，在 {@link #failed} 里体现。
     */
    @Schema(description = "失败明细，最多返回 100 条（超出的只计入 failed）。真正的失败原因通常只有一个，"
            + "逐条返回几百条只会让响应体膨胀。")
    private List<FailureDTO> failures;

    /**
     * 处理完后系统里还剩多少条待审（{@code status=0}）。
     *
     * <p>它的用途是<b>让调用方知道要不要再来一次</b>：批量审核有条数上限，
     * 而线上可能积压着上千条待审。前端据此显示"还剩 N 条，继续处理"，
     * 或者由运维脚本循环调用直到本值为 0。
     *
     * <p>注意它统计的是<b>全部</b>待审，而不是"符合你这次筛选条件的剩余" ——
     * 后者需要对同一组条件再查一次 count，代价翻倍，而这个页面上的筛选条件
     * （域名、命中词）本来就是用来分批处理的手段，不是常驻的过滤视图。
     */
    @Schema(description = "处理完后还剩多少条待审（status=0），用于判断要不要再来一次。"
            + "统计的是全部待审，不是「符合本次筛选条件的剩余」。", example = "6")
    private Long remainingPending;

    /** 单条失败明细。 */
    @Data
    @Builder
    @Schema(description = "单条失败明细。")
    public static class FailureDTO implements Serializable {

        /** 内容主键（{@code t_search_content.id}）。 */
        @Schema(description = "内容主键（t_search_content.id）。", example = "1024")
        private Long contentId;

        /** 失败原因。直接展示给运维看，因此是中文短句而不是异常类名。 */
        @Schema(description = "失败原因的中文短句。", example = "内容不存在（可能已被删除或撤回）")
        private String reason;
    }
}
