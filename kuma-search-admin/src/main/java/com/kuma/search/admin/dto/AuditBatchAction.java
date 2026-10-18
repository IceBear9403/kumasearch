package com.kuma.search.admin.dto;

import com.kuma.search.common.exception.BizException;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 批量审核支持的动作。
 *
 * <h3>为什么这里只有两个，而不是照抄单条接口的三个</h3>
 * 单条接口有放行 / 驳回 / 撤回三个动作（见 {@code CrawlAuditService} 的类注释），
 * 批量这里<b>刻意不给撤回</b>：
 * <ul>
 *   <li>放行与驳回只决定"能不能被搜到"，且驳回是可逆的（再放行一次即可）；</li>
 *   <li>撤回让<b>已经对外可见过</b>的内容消失，且不可逆。</li>
 * </ul>
 * "全选当前页 → 驳回"与"全选当前页 → 撤回"在界面上只差一个字，后果却相差一个数量级。
 * 因此撤回始终逐条、带二次确认。<b>这个不对称是有意的，不要为了"三个动作保持一致"
 * 而把撤回加进来</b> —— 那等于给一次误点准备了几百个受害者。
 *
 * <h3>为什么用字符串接收而不是直接让 Jackson 反序列化成枚举</h3>
 * 与 {@link EmailScene} 同一套理由：未知取值要给出一句人能看懂的错误
 * （"action 取值非法，只支持 APPROVE / REJECT"），而 Jackson 反序列化失败
 * 只会抛一个 {@code HttpMessageNotReadableException}，前端拿到的是 400 与一段
 * 与业务无关的解析异常信息。
 */
@Schema(description = "批量审核动作。刻意不含撤回（TAKEDOWN）：撤回不可逆且作用于已对外可见的内容，始终逐条进行。")
public enum AuditBatchAction {

    /** 放行：置为已发布，走既有写链路进 ES。 */
    @Schema(description = "放行：置为已发布（status=1），走既有写链路进 ES")
    APPROVE("放行"),

    /** 驳回：置为已驳回，不进 ES。可逆。 */
    @Schema(description = "驳回：置为已驳回（status=2），不进 ES。可逆，再放行一次即可恢复")
    REJECT("驳回");

    /** 中文动作名，用于日志与返回给前端的提示。 */
    private final String label;

    AuditBatchAction(String label) {
        this.label = label;
    }

    /** 中文动作名。 */
    public String label() {
        return label;
    }

    /**
     * 解析前端传来的动作字符串。
     *
     * <p>大小写不敏感，但不接受未知值 —— 未知动作直接报参数错误，
     * 而不是"默认按放行处理"：批量放行是不可逆的对外动作，
     * 一次拼写错误不该以"几百条内容被发布出去"收场。
     *
     * @throws BizException 为空或不是已知取值
     */
    public static AuditBatchAction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.paramInvalid("action 不能为空，批量审核必须显式指定动作");
        }
        String normalized = raw.trim().toUpperCase();
        for (AuditBatchAction action : values()) {
            if (action.name().equals(normalized)) {
                return action;
            }
        }
        throw BizException.paramInvalid("action 取值非法，只支持 APPROVE / REJECT");
    }
}
