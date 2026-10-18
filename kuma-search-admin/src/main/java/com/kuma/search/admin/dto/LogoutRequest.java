package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 登出请求体，对应 {@code POST /api/auth/logout}。
 *
 * <p>整个请求体是<b>可选</b>的（字段不加 {@code @NotBlank}，控制器用
 * {@code @RequestBody(required = false)} 接收），原因有两个：
 * <ul>
 *   <li>不带 refresh token 的登出仍然要能工作 —— access token 在黑名单里、refresh token 到期自然失效，
 *       只是安全性弱一些，不该因此让用户卡在登录态；</li>
 *   <li>上线过程中前端与后端不会同时切换，老版前端会继续发一个没有 body 的请求。
 *       强制必填会让这段过渡期里所有人的登出都失败。</li>
 * </ul>
 */
@Data
@Schema(description = "登出请求体，整体可选（可为空）")
public class LogoutRequest {

    /**
     * 当前会话的 refresh token。
     *
     * <p>只有把它一并拉黑，登出才真的结束了这次会话：refresh token 的有效期是 7 天，
     * 仅凭它就能换出新的 access token，所以"只登出 access token"等于没登出。
     */
    @Schema(description = "当前会话的 refresh token；不传则只能拉黑 access token", example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0...")
    private String refreshToken;
}
