package com.kuma.search.constant;

import java.util.List;
import java.util.Set;

/**
 * 内容字段的「落点」与「谁能写」—— 三个名单，全站只有这一份。
 *
 * <h3>为什么必须集中在一处</h3>
 * {@code t_search_content} 有固定列，其余字段要落到 {@code extra} JSON 里：这是
 * {@code DocumentConverter#flatten} 的既定行为（固定列优先，extra 用 {@code putIfAbsent} 补上，
 * 且只保留 {@code field_config} 里声明过的字段名）。但"哪些字段是固定列"这件事，
 * 表格渲染（要决定把值写到请求体顶层还是 extra）、投稿白名单（决定允许写哪些键）
 * 都要用到。名单一旦有两份，症状是「表单填的值静默丢失」——不报错、不入库、也搜不出，
 * 排查方向会跑到 ES 上去。
 *
 * <p>⚠️ {@link #ENTITY_COLUMNS} 与 {@code DocumentConverter#flatten} 是强耦合的：
 * 那里每 {@code doc.put} 一个列名，这里就要有对应的项；反过来也一样。
 * 改动任何一侧都必须同时看另一侧。
 */
public final class ContentFields {

    private ContentFields() {
    }

    /**
     * {@code t_search_content} 的业务列 —— 表单里的同名字段写进这些列，而不是 {@code extra}。
     *
     * <p>不含 {@code id} / {@code biz_id} / {@code type_code}：它们是三个保留字段，
     * 见 {@link #RESERVED_FIELD_NAMES}。
     *
     * <p>也不含 {@code create_time} / {@code update_time} / {@code deleted}：
     * 前两个由 MyBatis-Plus 自动填充，第三个是逻辑删除标记，都不该由表单设置。
     */
    public static final List<String> ENTITY_COLUMNS = List.of(
            "title", "content", "tags", "categoryId", "authorId",
            "price", "bizWeight", "status", "publishTime");

    /**
     * 保留字段名：它们会出现在 {@code field_config} 里（因为 mapping 需要声明它们），
     * 但**不是"内容字段"**，表单不画、投稿也不允许写。
     *
     * <p>为什么要显式排除而不是"让它们自然不出现在表单里"：
     * {@code id} / {@code bizId} / {@code typeCode} 的值由服务端决定
     * （{@code bizId} 是 ES 文档 _id 的来源，让用户改等于允许覆盖别人的文档），
     * 一旦被渲染成输入框，就是一个看起来能填、实际会把数据写坏的入口。
     */
    public static final Set<String> RESERVED_FIELD_NAMES = Set.of("id", "bizId", "typeCode");

    /**
     * 前台投稿时由**服务端强制接管**的字段：表单不渲染，即使请求里带了也会被覆盖。
     *
     * <ul>
     *   <li>{@code status} —— 投稿一律落 0（待审）。不接管就等于用户可以自己把稿子标成"已发布"
     *       直接对外可见，绕开整个审核队列；</li>
     *   <li>{@code authorId} —— 必须写当前登录用户。不接管则可以把稿子挂到别人名下，
     *       而"只能改自己的稿子"这条规则正是靠它判断的；</li>
     *   <li>{@code bizWeight} —— 它参与检索排序加权，属于"审核者给的排面"，
     *       不该由投稿人自己设（否则人人把权重拉满，等于这个字段失效）。</li>
     * </ul>
     *
     * <p>后台内容维护页不受这个名单限制：那里的操作者已经过了 {@code admin:content:manage}，
     * 逐条设状态、调权重、改作者都是它的正常工作。
     */
    public static final List<String> SERVER_MANAGED_ON_SUBMIT = List.of("status", "authorId", "bizWeight");

    /** 该字段名是否是保留字段（表单不画、投稿不许写）。 */
    public static boolean isReserved(String fieldName) {
        return fieldName != null && RESERVED_FIELD_NAMES.contains(fieldName);
    }

    /** 该字段名是否落 {@code t_search_content} 的固定列（false 表示要写进 {@code extra}）。 */
    public static boolean isEntityColumn(String fieldName) {
        return fieldName != null && ENTITY_COLUMNS.contains(fieldName);
    }
}
