package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 字段配置 JSON 校验请求体，对应 {@code POST /api/admin/index-type/validate-field-config}。
 */
@Data
@Schema(description = "字段配置 JSON 校验请求体")
public class FieldConfigValidateRequest {

    /** 待校验的 {@code field_config} JSON 字符串。 */
    @Schema(description = "待校验的 field_config JSON 字符串",
            example = "{\"title\":{\"type\":\"text\",\"analyzer\":\"ik_max_word\"}}")
    private String fieldConfig;
}
