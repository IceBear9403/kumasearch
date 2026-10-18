package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 当前登录用户信息，对应 {@code GET /api/auth/me}。不含密码。
 *
 * <p>数据来自<b>两个来源</b>，这一点在改动本类时要知道：
 * {@code nickname} 与 {@code email} 是展示字段，回查数据库取（它们不在令牌里）；
 * {@code username} / {@code roles} / {@code permissions} 取自本次请求的令牌，
 * 与鉴权时看到的完全一致。见 {@code ProfileService#currentProfile}。
 */
@Data
@Builder
@Schema(description = "当前登录用户信息（不含密码）")
public class UserInfoResponse {

    @Schema(description = "用户 ID", example = "1")
    private Long userId;
    @Schema(description = "登录名", example = "admin")
    private String username;
    @Schema(description = "昵称", example = "管理员")
    private String nickname;
    /**
     * 邮箱，个人中心要回显。
     *
     * <p>这是<b>本人的</b>邮箱，返回给本人不构成泄露。但要注意：
     * 它只在 {@code /api/auth/me} 与登录响应里出现，任何"查别人"的接口
     * （用户列表、内容详情的作者信息等）都不该带上它。
     */
    @Schema(description = "邮箱（本人）", example = "user@example.com")
    private String email;
    @Schema(description = "角色列表", example = "[\"USER\"]")
    private List<String> roles;
    @Schema(description = "权限码列表", example = "[\"search:read\"]")
    private List<String> permissions;
}
