package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量审核的请求体，对应 {@code POST /api/admin/crawl/audit/batch}。
 *
 * <h3>两种定位方式，二选一</h3>
 * <table border="1">
 *   <caption>定位方式</caption>
 *   <tr><th>方式</th><th>传什么</th><th>典型场景</th></tr>
 *   <tr><td>按 id</td><td>{@link #contentIds}</td>
 *       <td>"我在列表里勾中的这几条" —— 逐页审阅后处理当前页</td></tr>
 *   <tr><td>按条件</td><td>{@link #domain} / {@link #matchedWords}</td>
 *       <td>"把 zgbk.com 剩下的待审内容全部放行" —— 清理积压</td></tr>
 * </table>
 * 两者同时传时<b>按 id 处理</b>并在服务层忽略条件，不报错：
 * 勾选是更明确的意图，为这种"多给了一个无关字段"的情况让人吃一个 400
 * 属于自找麻烦。但前端不该依赖这个宽容，它只是兜底。
 *
 * <h3>为什么不放进单条接口的请求体里</h3>
 * 与 {@link AuditActionRequest} 分开的两个理由是：单条请求体只有"理由"一个字段，
 * 而批量请求体有五个；更关键的是<b>两者的危险等级不同</b>，
 * 混在一个 DTO 里会让"批量"在这条链路上变得不那么显眼 ——
 * 而它正是那个一次能影响几百条内容的接口。
 */
@Data
@Schema(description = "批量审核请求体。contentIds 与 domain/matchedWords 二选一："
        + "前者用于「处理勾中的这几条」，后者用于「处理筛选条件下的全部待审」。两者同时传时按 id 处理。")
public class BatchAuditRequest {

    /**
     * 动作。
     *
     * <p>用字符串而不是枚举类型接收，理由见 {@link AuditBatchAction} 的类注释。
     * 非必填的校验放在 {@code AuditBatchAction.parse} 里，避免两处各报一次错、文案还不一样。
     */
    @NotBlank(message = "action 不能为空，批量审核必须显式指定动作")
    @Schema(description = "审核动作，取值 APPROVE（放行）/ REJECT（驳回）；大小写不敏感。"
            + "刻意不支持 TAKEDOWN（撤回）：撤回不可逆，始终逐条进行。", example = "APPROVE")
    private String action;

    /**
     * 要处理的内容主键列表（{@code t_search_content.id}）。
     *
     * <p>上限 2000 与服务层的 {@code BATCH_LIMIT_MAX} 一致。<b>这里也校验一次是有意的</b>：
     * 服务层的上限是给所有调用方（含将来的定时任务）兜底的，而
     * {@code @Size} 能让前端在请求还没进业务逻辑时就拿到明确的参数错误。
     */
    @Size(max = 2000, message = "单次批量审核最多 2000 条，请分批提交")
    @Schema(description = "要处理的内容主键列表。最多 2000 条，超出报参数错误（不静默截断）。"
            + "与 domain/matchedWords 二选一。", example = "[1024, 1025, 1026]")
    private List<Long> contentIds;

    /**
     * 按来源域名筛选。
     *
     * <p>匹配的是 {@code extra} JSON 里的 {@code domain} 字段（与审核列表的筛选口径一致），
     * 因此传 {@code www.zgbk.com} 这样的<b>完整域名</b>，而不是"当前页"。
     */
    @Schema(description = "来源域名，匹配 t_search_content.extra 里的 domain 字段（与审核列表同一个筛选口径）。"
            + "传完整域名，如 www.zgbk.com。", example = "www.zgbk.com")
    private String domain;

    /** 按命中的敏感词筛选。典型用途是找出某个词误判的那一批，一次性驳回并回头调词库。 */
    @Schema(description = "命中的敏感词，匹配 extra 里的 matchedWords 字段。用于把某个误判词命中的一批一次处理掉。",
            example = "彩票")
    private String matchedWords;

    /**
     * 按条件处理时的条数上限。
     *
     * <p>省略时服务层取 200。这个值<b>不是</b>"想处理多少条"，
     * 而是"一次请求最多做多少条"—— 响应里的 {@code remainingPending}
     * 告诉调用方还剩多少，需要时再调一次。
     */
    @Min(value = 1, message = "limit 至少为 1")
    @Max(value = 2000, message = "limit 最大 2000")
    @Schema(description = "按条件处理时的条数上限，省略取 200，最大 2000。"
            + "这不是「想处理多少条」而是「一次请求最多做多少条」，剩余数量在响应的 remainingPending 里。",
            example = "200")
    private Integer limit;

    /**
     * 审核理由。
     *
     * <p>批量时这条理由会写进<b>每一条</b>的审核日志。因此它的措辞应当能覆盖整批，
     * 例如"zgbk.com 站点内容质量不合格，整站排除"，而不是"这一条有问题"。
     */
    @Size(max = 500, message = "reason 长度不能超过 500")
    @Schema(description = "审核理由，会写进本批每一条的审核日志。措辞应能覆盖整批，例如「该站点内容质量不合格，整站排除」。",
            example = "该站点内容质量不合格，整站排除")
    private String reason;
}
