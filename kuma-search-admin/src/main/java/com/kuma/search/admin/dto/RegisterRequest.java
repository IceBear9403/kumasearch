package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求体，对应 {@code POST /api/auth/register}。
 *
 * <p>注册成功后<b>直接签发令牌</b>（注册即登录），返回体与 {@code POST /api/auth/login}
 * 完全一致（同一个 {@link TokenResponse}）。让用户注册完还要再登录一次是纯粹的多余步骤。
 *
 * <p>校验顺序（在 {@code RegisterService} 里，顺序本身就是安全要求）：
 * 滑块 → 邮箱验证码 → 用户名/邮箱唯一性 → 建用户 → 授 USER 角色 → 签令牌。
 * 唯一性检查放在验证码<b>之后</b>：否则任何人都能用注册接口的
 * "用户名已存在"来批量枚举本站有哪些账号，而验证码是这条路径上唯一的门槛。
 */
@Data
@Schema(description = "注册请求体")
public class RegisterRequest {

    /**
     * 登录账号。
     *
     * <p>只允许字母、数字、下划线与连字符：用户名会出现在日志、后台列表与
     * 「最后登录」这类审计信息里，放开到任意字符会让日志中出现换行、控制字符，
     * 甚至被用来伪造日志行的结构。
     *
     * <p>长度下限 3 与 {@code t_sys_user.username} 的既有数据保持一致。
     */
    @NotBlank(message = "username 不能为空")
    @Size(min = 3, max = 64, message = "username 长度需在 3~64 之间")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "username 只能包含字母、数字、下划线与连字符")
    @Schema(description = "登录账号，3~64 位，仅字母、数字、下划线与连字符", example = "new_user")
    private String username;

    /**
     * 邮箱。落库前由 {@code EmailNormalizer} 归一化。
     */
    @NotBlank(message = "email 不能为空")
    @Email(message = "email 格式不正确")
    @Size(max = 254, message = "email 长度不能超过 254")
    @Schema(description = "邮箱", example = "user@example.com")
    private String email;

    /**
     * 邮箱收到的 6 位验证码。
     *
     * <p>只做长度与位数约束，不在 DTO 层判"对不对"：验证码是 BCrypt 存储的，
     * 校验必须走 {@code EmailCodeService}，那里同时负责失败计数与达上限作废。
     * 在这里自己比一次字符串，会绕过那套计数。
     */
    @NotBlank(message = "emailCode 不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "emailCode 必须是 6 位数字")
    @Schema(description = "邮箱收到的 6 位验证码（REGISTER 场景）", example = "123456")
    private String emailCode;

    /**
     * 明文密码。<b>严禁写入任何日志</b>。
     *
     * <p>上限 128 与 {@code LoginRequest#password} 保持一致，理由相同：
     * BCrypt 只取前 72 字节。下限与复杂度不在这里写死，交给
     * {@code PasswordPolicy} —— 登录侧也要用同一套规则说明，两处写死必然漂移。
     */
    @NotBlank(message = "password 不能为空")
    @Size(max = 128, message = "password 长度不能超过 128")
    @Schema(description = "明文密码，强度规则由 PasswordPolicy 校验", example = "P@ssw0rd123")
    private String password;

    /**
     * 昵称。可空，为空时由服务端取用户名。
     */
    @Size(max = 64, message = "nickname 长度不能超过 64")
    @Schema(description = "昵称，可空，为空时由服务端取用户名", example = "小明")
    private String nickname;

    /** 滑块校验凭证，一次性，见 {@link EmailCodeRequest#getCaptchaVerification()}。 */
    @NotBlank(message = "captchaVerification 不能为空")
    @Size(max = 512, message = "captchaVerification 长度不能超过 512")
    @Schema(description = "滑块校验凭证（一次性）", example = "7f3a9c1e5b2d4a6f0e8c1b3d5f7a9c2e")
    private String captchaVerification;
}
