package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求体，对应 {@code POST /api/auth/login}。
 */
@Data
@Schema(description = "密码登录请求体")
public class LoginRequest {

    /**
     * 登录名。长度上限与 {@code t_sys_user.username} 的列宽一致。
     *
     * <p>加长度限制不只是为了对齐数据库：登录失败计数是按用户名建 Redis key 的，
     * 没有上限的话，攻击者可以用超长（甚至每次都不重复的）用户名刷出大量大 key 撑爆内存。
     */
    @NotBlank(message = "username 不能为空")
    @Size(max = 64, message = "username 长度不能超过 64")
    @Schema(description = "登录名", example = "admin")
    private String username;

    /**
     * 明文密码仅在本次请求内使用，严禁写入任何日志。
     *
     * <p>上限 128 是因为 BCrypt 只取前 72 字节，更长的输入没有任何额外强度，
     * 却要白白消耗一次散列与一次大请求体的解析。
     */
    @NotBlank(message = "password 不能为空")
    @Size(max = 128, message = "password 长度不能超过 128")
    @Schema(description = "明文密码", example = "admin123")
    private String password;

    /**
     * 滑块校验凭证。
     *
     * <p><b>刻意不是 {@code @NotBlank}。</b>这个字段是否必需取决于
     * {@code kuma.captcha.enabled}：启用时它是必填的，未启用时前端根本不会产生它。
     * 在 DTO 上写死必填会让"未启用验证码"的部署连密码登录都用不了 ——
     * 那是把一次配置默认值变成一次停服。
     *
     * <p>两种情形下的处理因此不同，且这个差异是<b>有意的</b>（见
     * {@code AuthService#verifyCaptchaForPasswordLogin}）：
     * <ul>
     *   <li>验证码已启用 → 缺失即校验失败，不给任何绕过余地；</li>
     *   <li>验证码未启用 → 这条链路与改造前完全一致（本来就没有这一层）。</li>
     * </ul>
     * 注意这不适用于注册与邮箱码登录：那两条链路的滑块是<b>固定必需</b>的，
     * 未启用时会直接拒绝服务，而不是跳过。
     */
    @Size(max = 512, message = "captchaVerification 长度不能超过 512")
    @Schema(description = "滑块校验凭证；kuma.captcha.enabled=true 时必填", example = "7f3a9c1e5b2d4a6f0e8c1b3d5f7a9c2e")
    private String captchaVerification;
}
