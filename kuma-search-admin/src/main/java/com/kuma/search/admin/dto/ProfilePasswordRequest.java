package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码，对应 {@code PUT /api/auth/profile/password}。
 *
 * <p><b>明文密码严禁写入任何日志</b>（与 {@code LoginRequest} 同一条纪律）：
 * 本类的 {@code toString()} 由 Lombok 生成、会包含两个字段，
 * 因此任何时候都不要把这个对象打进日志、也不要在全局异常处理里回显请求体。
 */
@Data
@Schema(description = "修改密码请求体")
public class ProfilePasswordRequest {

    /**
     * 当前密码。
     *
     * <p>要求提供它，是因为这个接口只凭一枚令牌就能调用：不验旧密码的话，
     * 任何拿到令牌的人（共用设备、令牌泄露）都能直接把账号锁掉 ——
     * 而"改密码"恰恰是用户发现异常后用来夺回账号的动作，它不该成为攻击者的工具。
     */
    @NotBlank(message = "oldPassword 不能为空")
    @Size(max = 128, message = "oldPassword 长度不能超过 128")
    @Schema(description = "当前密码", example = "OldP@ss123")
    private String oldPassword;

    /**
     * 新密码。强度规则由 {@code PasswordPolicy} 校验，不在这里写死 ——
     * 与注册侧共用同一份规则，两处写死必然漂移。
     */
    @NotBlank(message = "newPassword 不能为空")
    @Size(max = 128, message = "newPassword 长度不能超过 128")
    @Schema(description = "新密码，强度规则由 PasswordPolicy 校验", example = "NewP@ss456")
    private String newPassword;
}
