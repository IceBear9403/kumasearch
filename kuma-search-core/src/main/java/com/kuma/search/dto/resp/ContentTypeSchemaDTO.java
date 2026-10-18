package com.kuma.search.dto.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 一个类型的**内容表单结构** —— 后台内容维护页与前台投稿页据此渲染动态表单。
 *
 * <h3>为什么要有这个接口，而不是让前端写死 article 的那几个字段</h3>
 * 类型是配置驱动的：字段清单在 {@code t_index_type_config.field_config} 里，加一个字段
 * 不该改前端代码。写死的结果是"后台能配、前台录不了"，配置的一半能力被前端吃掉。
 *
 * <h3>它只描述「表单要画什么」，不泄漏「索引怎么建」</h3>
 * 刻意<b>不</b>返回 {@code analyzer} / {@code searchAnalyzer} / {@code boost} / {@code filters}
 * 这些索引期参数：它们决定的是"这个字段怎么被检索"，而不是"这个字段该填什么"。
 * 返回它们只会让表单渲染器多出一堆它无法解释的开关。
 *
 * <h3>fields 与 columns 的关系（前端最需要搞清楚的一条）</h3>
 * {@code t_search_content} 有一批固定列（title/content/tags/...），其余字段要落到
 * {@code extra} JSON 里。这件事由 {@link ContentFieldSchemaDTO#isInExtra()} 逐个字段告诉前端，
 * 前端不必自己维护这份名单 —— 落点规则只有一个来源，就是后端的
 * {@code DocumentConverter#flatten}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "一个类型的内容表单结构：可编辑字段清单 + 每个字段该落在哪")
public class ContentTypeSchemaDTO implements Serializable {

    @Schema(description = "类型编码", example = "article")
    private String typeCode;

    @Schema(description = "类型展示名", example = "文章")
    private String typeName;

    /**
     * 是否允许前台用户向该类型投稿。
     *
     * <p>后台内容页据此决定要不要显示"投稿开关"这一项的当前状态；
     * 前台投稿页只拿得到 {@code allowUserSubmit=1} 的类型（服务端已过滤）。
     */
    @Schema(description = "是否允许前台用户投稿：true 允许 / false 不允许")
    private boolean allowUserSubmit;

    /**
     * 可编辑的业务字段清单（已排除 id / bizId / typeCode 这类保留字段）。
     */
    @Schema(description = "可编辑字段清单。id / bizId / typeCode 属保留字段，不会出现在这里")
    private List<ContentFieldSchemaDTO> fields;

    /**
     * 表单提交后必须由服务端强制接管、不允许填写的字段名。
     *
     * <p>前台投稿页用它来<b>不渲染</b>这些输入框（渲染了也会被后端覆盖，等于骗人）；
     * 后台内容页则可以在审核时手工设置它们。
     */
    @Schema(description = "服务端强制接管的字段名（投稿页不渲染），如 status / authorId / bizWeight")
    private List<String> serverManagedFields;
}
