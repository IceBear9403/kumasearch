package com.kuma.search.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 检索接口的请求体，对应 {@code POST /api/search}。
 *
 * <p>与内部的 {@code SearchParam} 分开两个类，不是冗余：
 * DTO 是<b>对外契约</b>，带着校验注解与向后兼容的约束（字段只能加不能删）；
 * {@code SearchParam} 是<b>内部模型</b>，可以随实现自由重构。
 * 把两者混为一谈的结果是：任何一次内部重构都会变成对前端的破坏性变更。
 */
@Data
@Schema(description = "站内检索请求体，对应 POST /api/search")
public class SearchRequestDTO implements Serializable {

    /** 索引类型编码。 */
    @Schema(description = """
            必填。索引类型编码，取值来自 GET /api/search/types（可检索类型列表），如 article。
            填一个不存在或已停用的类型会返回 41001；webpage 不在该列表中，站外采集内容请走 POST /api/search/web。""",
            example = "article")
    @NotBlank(message = "typeCode 不能为空")
    private String typeCode;

    /** 搜索关键词。允许为空 —— 此时退化为「分类页」式的纯过滤 + 聚合查询。 */
    @Schema(description = "搜索关键词。允许为空 —— 此时退化为「分类页」式的纯过滤 + 聚合查询。", example = "手机")
    @Size(max = 200, message = "关键词长度不能超过 200")
    private String keyword;

    /** 指定检索字段。为空时用类型配置里的默认字段及其权重。 */
    @Schema(description = "指定参与检索的字段。为空时使用类型配置里的默认检索字段及其权重。", example = "[\"title\",\"content\"]")
    private List<String> searchFields;

    /** 精确过滤：字段名 -> 允许值列表。字段名会在服务端做白名单校验。 */
    @Schema(description = "精确过滤（term/terms）：字段名 → 允许值列表，同一个字段的多个取值之间是「或」关系。"
            + "字段名会在服务端做白名单校验（不信任前端），不在类型配置里的字段返回 41002。",
            example = "{\"categoryId\":[\"1001\",\"1002\"]}")
    private Map<String, List<String>> filters;

    /** 范围过滤。 */
    @Schema(description = "范围过滤。每个元素针对一个字段给出上下界，多个条件之间为 AND 关系。")
    private List<RangeFilterDTO> rangeFilters;

    /** 需要聚合的字段。 */
    @Schema(description = "需要聚合的字段，用于生成响应里的 facets（左侧筛选面板）。数组顺序决定响应中 facets 的顺序；字段名走服务端白名单校验。")
    private List<FacetDTO> facets;

    /** 排序规则。 */
    @Schema(description = "排序规则。数组顺序即优先级（前面的优先），字段名走服务端白名单校验；不传时使用类型配置里的默认排序。")
    private List<SortDTO> sorts;

    /** 高亮字段。 */
    @Schema(description = "需要高亮的字段，命中关键词的片段会在 HitDTO.highlights 里以 <em> 标记返回。", example = "[\"title\",\"content\"]")
    private List<String> highlightFields;

    /** 页码，从 1 开始。 */
    @Schema(description = "页码，从 1 开始，默认 1。与游标翻页互斥：cursorMode 为 true 时该值无效。", example = "1")
    @Min(value = 1, message = "页码从 1 开始")
    private Integer pageNum = 1;

    /**
     * 每页条数。上限由服务端再次校验（{@code kuma.elasticsearch.max-page-size}），
     * 不能只依赖这里的注解 —— 攻击者可以直接调用后端接口绕过前端。
     */
    @Schema(description = "每页条数，默认 10，上限 100。服务端还会用 kuma.elasticsearch.max-page-size 二次校验，不能只依赖这里的注解。", example = "10")
    @Min(value = 1, message = "每页条数至少为 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer pageSize = 10;

    /** 游标翻页时由上一页返回的 nextCursor 原样带回。 */
    @Schema(description = "游标翻页令牌，把上一页响应里的 nextCursor 原样带回即可继续向后翻。与页码分页互斥（游标模式不支持跳页）；pageNum 越过浅分页窗口却未带 cursor 时返回 30001。")
    private String cursor;

    /** 强制走游标翻页。浅分页窗口被突破时服务端也会自动切换。 */
    @Schema(description = "强制走游标翻页，默认 false。浅分页窗口被突破时服务端也会自动切换；自动切换后必须带上上一页返回的 cursor。", example = "false")
    private Boolean useCursor = false;

    /** 最低相关度分数。 */
    @Schema(description = "最低相关度分数。低于该分数的命中会被过滤掉，不传表示不限制。", example = "0")
    private Float minScore;

    /**
     * 是否精确统计总命中数，默认 true。
     *
     * <p>关掉之后 ES 会跳过一次跨全分片的计数遍历，只给出一个下界估算值
     * （{@code relation: gte}），在结果集很大时能显著降低查询耗时。
     * 高 QPS 场景下如果前端并不展示"共 N 条"，把它设为 false 是笔划算的买卖。
     *
     * <p>游标模式下该参数被忽略 —— 那时本来就不返回 total。
     */
    @Schema(description = "是否精确统计总命中数，默认 true。设为 false 时 ES 跳过一次跨全分片的计数遍历，total 变成估算下界（relation: gte），大结果集下能明显降低查询耗时；游标模式下该参数被忽略。", example = "true")
    private Boolean trackTotalHits = true;

    /** 是否启用模糊匹配（纠错）。 */
    @Schema(description = "是否启用模糊匹配（对关键词做错别字容忍），默认 true。", example = "true")
    private Boolean fuzzy = true;

    /** 是否启用同义词扩展。 */
    @Schema(description = "是否启用同义词扩展，默认 true。需要该类型配置了同义词词典，否则开了也只是引用不存在的子字段。", example = "true")
    private Boolean synonym = true;

    /** 是否启用拼音检索。 */
    @Schema(description = "是否启用拼音检索，默认 true。需要该类型配置了拼音分析器。", example = "true")
    private Boolean pinyin = true;

    /** 每个 facet 返回的桶数量上限。 */
    @Schema(description = "每个 facet 返回的桶数量上限。不传时用服务端 kuma.search.facet-size；单个 facet 自带的 size 优先于它。", example = "10")
    private Integer aggSize;

    @Data
    @Schema(description = "范围过滤条件：对某个字段给定上下界。四个边界都可选，同时给出时按 AND 组合。")
    public static class RangeFilterDTO implements Serializable {

        @Schema(description = "字段名，需在该类型配置中声明且允许范围过滤。", example = "price")
        private String field;

        @Schema(description = "大于等于（含）。值类型与该字段类型一致（数值或时间字符串）。", example = "1000")
        private Object gte;

        @Schema(description = "小于等于（含）。", example = "8000")
        private Object lte;

        @Schema(description = "大于（不含）。", example = "1000")
        private Object gt;

        @Schema(description = "小于（不含）。", example = "8000")
        private Object lt;
    }

    @Data
    @Schema(description = "需要聚合的字段：field 指定聚合哪个字段，size 指定该字段返回的桶数量上限。")
    public static class FacetDTO implements Serializable {

        @Schema(description = "聚合字段名，需在该类型配置中允许聚合（facetable）。", example = "categoryId")
        private String field;

        @Schema(description = "该字段返回的桶数量上限；不传时用请求级的 aggSize，再退到服务端 kuma.search.facet-size。", example = "10")
        private Integer size;
    }

    @Data
    @Schema(description = "排序规则：按数组中的顺序依次生效（前面的优先）。")
    public static class SortDTO implements Serializable {

        @Schema(description = "排序字段名，需在该类型配置中允许排序；_score 表示按相关度排序。", example = "publishTime")
        private String field;

        @Schema(description = "排序方向：asc / desc，默认 desc。", example = "desc")
        private String order = "desc";

        @Schema(description = "多值字段的排序取值模式，如 min / max / avg；单值字段可留空。", example = "max")
        private String mode;
    }
}
