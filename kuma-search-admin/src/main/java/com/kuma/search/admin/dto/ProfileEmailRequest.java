package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 换绑邮箱，对应 {@code PUT /api/auth/profile/email}。
 *
 * <h3>验证码发到<b>新</b>邮箱，不是旧邮箱</h3>
 * 这是本接口最关键的一处设计，也是与"改密码"最不一样的地方：
 * <ul>
 *   <li>发到新邮箱，证明的是<b>新邮箱归你所有</b> —— 这正是换绑要确立的那件事；</li>
 *   <li>发到旧邮箱，证明的只是"你还能收到旧邮箱的信"，而那件事由"你正持有一枚有效的
 *       access token"已经隐含了。它会放过"令牌被盗后把邮箱改到自己名下"这条路径，
 *       而那一步一旦成功，真正的账号所有者就再也收不到任何找回邮件了。</li>
 * </ul>
 * 代价是失去"旧邮箱还能收信"这一层确认 —— 因此这里要求调用方已登录（持有有效令牌），
 * 由令牌承担那一层的职责。
 *
 * <p>场景码用 {@code CHANGE_EMAIL}：与 REGISTER 共用"新邮箱必须未被占用"的检查，
 * 但验证码的 Redis key 带场景名，因此两处发出来的码<b>不能互相重放</b>。
 */
@Data
@Schema(description = "换绑邮箱请求体")
public class ProfileEmailRequest {

    /** 新邮箱。落库前由 {@code EmailNormalizer} 归一化（trim + 转小写）。 */
    @NotBlank(message = "newEmail 不能为空")
    @Email(message = "newEmail 格式不正确")
    @Size(max = 254, message = "newEmail 长度不能超过 254")
    @Schema(description = "新邮箱", example = "new@example.com")
    private String newEmail;

    /**
     * 发往新邮箱的 6 位验证码。
     *
     * <p>只做格式约束，不在 DTO 层判对错：校验要走 {@code EmailCodeService}，
     * 那里同时负责失败计数与达上限作废，在这里自己比一次字符串会绕过那套计数。
     */
    @NotBlank(message = "emailCode 不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "emailCode 必须是 6 位数字")
    @Schema(description = "发往新邮箱的 6 位验证码（CHANGE_EMAIL 场景）", example = "123456")
    private String emailCode;
}
