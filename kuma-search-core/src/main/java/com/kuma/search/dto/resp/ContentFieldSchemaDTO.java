package com.kuma.search.dto.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 表单里的一个字段 —— 只包含"画控件需要知道的东西"。
 *
 * <p>与 {@code es.mapping.FieldConfig} 的区别不是冗余，而是**职责边界**：
 * FieldConfig 是"这个字段怎么被索引"（分词器、权重、子字段），这里是"这个字段该填什么、怎么校验"。
 * 两者恰好有交集（字段名、展示名、类型），交集部分由后端组装时映射过来。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "内容表单里的一个字段")
public class ContentFieldSchemaDTO implements Serializable {

    @Schema(description = "字段名。提交时作为 key 原样回传", example = "title")
    private String name;

    @Schema(description = "展示名，取自 field_config.label；为空时回退为字段名", example = "标题")
    private String label;

    /**
     * 字段类型，取 {@code es.mapping.FieldType} 的枚举名。
     *
     * <p>前端按它选控件：TEXT → 输入框/文本域、KEYWORD → 输入框、INTEGER/LONG/DOUBLE →
     * 数字框、BOOLEAN → 开关、DATE → 日期选择器。
     * {@code NESTED} 与 {@code GEO_POINT} <b>没有</b>合适的表单控件，前端应显示为
     * "请用 extra JSON 编辑"而不是硬塞一个文本框 —— 塞进去只会产出格式不对的 JSON。
     */
    @Schema(description = "字段类型（FieldType 枚举名）", example = "TEXT")
    private String type;

    /** 是否多值（数组），如 tags。多值时控件要允许多选。 */
    @Schema(description = "是否多值（数组）字段", example = "false")
    private boolean multiValued;

    /**
     * DATE 字段期望的格式串，为空表示后端默认格式（同时认带空格与带 T 两种写法）。
     *
     * <p>返回它是为了让人知道"该填什么样的日期"，而不是让前端自己解析 ——
     * 前端只需要把值原样传回，后端会用同一份格式串去索引。
     */
    @Schema(description = "DATE 字段的格式串，为空表示用后端默认格式", example = "yyyy-MM-dd HH:mm:ss")
    private String format;

    /**
     * 该字段是否落在 {@code t_search_content.extra} 里（false 表示它是固定列）。
     *
     * <p>前端据此决定提交时把它放在请求体顶层还是 extra JSON 里。
     * 这份判断只有一个来源（后端），前端不要自己维护固定列名单。
     */
    @Schema(description = "true 表示该字段写入 extra JSON，false 表示它是 t_search_content 的固定列")
    private boolean inExtra;
}
