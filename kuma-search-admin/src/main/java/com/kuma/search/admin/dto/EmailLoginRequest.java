package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 邮箱验证码登录请求体，对应 {@code POST /api/auth/login/email}。
 *
 * <p>返回体与 {@code POST /api/auth/login} <b>完全一致</b>（同一个 {@link TokenResponse}）：
 * 两条登录方式只在"如何证明你是你"这一步分叉，汇合之后共用 {@code AuthService} 的
 * 令牌签发/刷新/登出/黑名单链路，那条链路上<b>一行都不改</b>。
 *
 * <h3>⚠️ 本接口的响应必须与"账号是否存在"无关</h3>
 * 邮箱不存在与验证码错误必须返回<b>同一个错误码、同一条文案</b>。
 * 任何可区分的差异（错误码、文案、耗时、甚至返回体字段个数）都会把这个接口
 * 变成"批量探测哪些邮箱是本站用户"的工具 —— 而对一个已备案、对外公开的服务来说，
 * 用户邮箱清单本身就是有价值的数据。这条约束的落地位置在
 * {@code AuthService#loginByEmail}，不在这里。
 */
@Data
@Schema(description = "邮箱验证码登录请求体")
public class EmailLoginRequest {

    /** 收件邮箱。落库与查库前都会经过 {@code EmailNormalizer} 归一化。 */
    @NotBlank(message = "email 不能为空")
    @Email(message = "email 格式不正确")
    @Size(max = 254, message = "email 长度不能超过 254")
    @Schema(description = "收件邮箱", example = "user@example.com")
    private String email;

    /**
     * 邮箱收到的 6 位验证码。
     *
     * <p>与 {@link RegisterRequest#emailCode} 同一形态，但<b>场景不同</b>
     * （{@code EmailScene.LOGIN}）—— 注册场景发出的码在登录接口查不到记录，
     * 反之亦然。这是 {@code AuthKeys#emailCode} 把 scene 放进 key 的直接效果。
     */
    @NotBlank(message = "emailCode 不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "emailCode 必须是 6 位数字")
    @Schema(description = "邮箱收到的 6 位验证码（LOGIN 场景）", example = "123456")
    private String emailCode;

    /** 滑块校验凭证，一次性。 */
    @NotBlank(message = "captchaVerification 不能为空")
    @Size(max = 512, message = "captchaVerification 长度不能超过 512")
    @Schema(description = "滑块校验凭证（一次性）", example = "7f3a9c1e5b2d4a6f0e8c1b3d5f7a9c2e")
    private String captchaVerification;
}
