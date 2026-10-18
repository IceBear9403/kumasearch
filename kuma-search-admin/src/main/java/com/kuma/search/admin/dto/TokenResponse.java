package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 登录 / 刷新令牌的响应体。
 *
 * <p>刻意不含 password 字段 —— 密码散列在任何情况下都不得返回前端。
 */
@Data
@Builder
@Schema(description = "登录 / 刷新令牌的响应体")
public class TokenResponse {

    /** 访问令牌，前端存内存，随请求头 {@code Authorization: Bearer <token>} 提交。 */
    @Schema(description = "访问令牌，随请求头 Authorization: Bearer <token> 提交",
            example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0...")
    private String accessToken;

    /** 刷新令牌，仅用于换新令牌。 */
    @Schema(description = "刷新令牌，仅用于换新令牌", example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0...")
    private String refreshToken;

    /** access token 有效期（秒）。 */
    @Schema(description = "access token 有效期（秒）", example = "7200")
    private Long expiresIn;

    @Schema(description = "用户 ID", example = "1")
    private Long userId;
    @Schema(description = "登录名", example = "admin")
    private String username;
    @Schema(description = "昵称", example = "管理员")
    private String nickname;
    @Schema(description = "角色列表", example = "[\"USER\"]")
    private List<String> roles;
    @Schema(description = "权限码列表", example = "[\"search:read\"]")
    private List<String> permissions;
}
