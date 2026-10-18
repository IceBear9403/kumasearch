package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 人工审核动作的请求体，对应采集审核的放行 / 驳回 / 撤回三个接口。
 *
 * <p>三个接口共用一个请求体：它们的入参形状确实一样（只有一条理由），
 * 而"理由"的语义在三个场景下也完全一致 ——「为什么这么判」。
 * 拆成三个 DTO 只会让将来给理由加一个「理由分类」字段时要改三处。
 */
@Data
@Schema(description = "人工审核动作请求体（放行 / 驳回 / 撤回共用）")
public class AuditActionRequest {

    /**
     * 审核理由。
     *
     * <p><b>不设为必填</b>，但强烈建议填：这是给未来的自己看的。
     * 一条内容被驳回了、三个月后有人问"为什么驳回这一条"，
     * 没有理由的记录只能回答"当时有人点了驳回"。
     * 而把它设成必填会有另一个后果：审核人被迫在每条正常内容上填一个
     * 敷衍的字符，理由字段就此失去信号价值 —— 那比允许为空更糟。
     *
     * <p>上限 500 与 {@code t_crawl_audit_log.reason} 的列宽一致。
     * 超长不会报错而是被截断，因此这里必须拦住，让调用方拿到明确的参数错误。
     */
    @Size(max = 500, message = "reason 长度不能超过 500")
    @Schema(description = "审核理由，可选但建议填写，最多 500 字", example = "内容与索引类型不符")
    private String reason;
}
