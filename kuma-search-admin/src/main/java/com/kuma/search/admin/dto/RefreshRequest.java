package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新令牌请求体，对应 {@code POST /api/auth/refresh}。
 */
@Data
@Schema(description = "刷新令牌请求体")
public class RefreshRequest {

    @NotBlank(message = "refreshToken 不能为空")
    @Schema(description = "刷新令牌，仅用于换新令牌", example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0...")
    private String refreshToken;
}
