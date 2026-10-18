package com.kuma.search.es.mapping;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一个索引类型的<b>已解析定义</b> —— 由 {@code t_index_type_config} 的数据库行转换而来。
 *
 * <p>为什么要多这一层、而不直接让 {@code MappingGenerator} 吃数据库实体？
 * 因为数据库实体带着 {@code status}、{@code createTime}、MyBatis-Plus 的 {@code @TableName}
 * 这类持久化关注点；把它们泄进 mapping 生成逻辑，会让 mapping 的单元测试必须依赖数据库。
 * 这一层是持久化模型与索引模型之间的防腐层。
 */
@Data
@Builder
public class IndexDefinition implements Serializable {

    /** 类型编码，如 article。 */
    private String typeCode;

    /** 索引读写别名，如 kuma_article。物理索引名为 {@code alias + "_v" + version}。 */
    private String alias;

    /** 当前物理索引版本号。重建索引时 +1。 */
    private Integer version;

    private Integer shards;
    private Integer replicas;

    /** 业务主键字段名，其值用作 ES 的 {@code _id}，保证 upsert 幂等。 */
    private String pkField;

    /** 字段清单。 */
    private List<FieldConfig> fields;

    /** 默认全文检索字段（按 boost 降序）。为空时取所有 searchable=true 的字段。 */
    private List<String> defaultSearchFields;

    /** 默认高亮字段。 */
    private List<String> defaultHighlightFields;

    /** 默认排序，形如 {@code [{"field":"bizWeight","order":"desc"}]}。 */
    private List<Map<String, String>> defaultSort;

    /** 用于生成「摘要」的字段顺序，取第一个有值的字段作为摘要来源。 */
    private List<String> summaryFields;

    /** 物理索引名：别名 + 版本号。 */
    public String physicalIndexName() {
        return alias + "_v" + version;
    }

    /** 联想独立索引名。刻意与主索引分离，见 MappingGenerator 的说明。 */
    public String suggestIndexName() {
        return alias + "_suggest";
    }

    /**
     * 本类型是否有字段声明了 {@code suggest=true} —— 也就是「它到底需不需要联想索引」。
     *
     * <h3>这个方法为什么必须存在</h3>
     * 联想索引<b>不是每个类型都该有的</b>：completion 的 FST 常驻堆内存，因此"要不要给这个类型
     * 做下拉建议"这件事必须是一个配置项，而不是"所有类型一视同仁"。
     * 拿不准就不开，或者靠 {@code kuma.suggest.max-docs-per-type} 把灌入量压住。
     *
     * <p>⚠️ 这里曾经写着一条现在看来是错的推论：「采集类内容（几十万网页）配上下拉建议
     * 既撑内存又没意义」，并据此给 {@code webpage.title} 配了 {@code suggest=false}。
     * 它被推翻的原因是实践：搜索页的联想框是同一个，用户搜网页内容时一样需要下拉建议，
     * 而"有意义"与否并不由内容是自己录入的还是采集来的决定 —— 只由"这些标题是不是用户
     * 会去搜的东西"决定。真实代价（堆内存）改由条数上限承担，见
     * {@code kuma.suggest.max-docs-per-type}。改判的完整记录在
     * {@code docs/03-data-design.md} §3.1.3。
     *
     * <p>而在本次修复之前，代码<b>从来没有读过这个开关</b>：
     * {@code MappingGenerator#buildSuggestIndexJson} 无条件生成联想 mapping、
     * {@code DynamicIndexManager#createIndex} 无条件建这个索引、
     * {@code EsSearchGateway#suggest} 无条件去查它。三者叠加的结果就是
     * 「配置里写了不用联想，运行时却每次都去查一个不该存在的索引」——
     * 线上表现为每个 {@code type=webpage} 的联想请求都刷一条
     * {@code no such index [kuma_webpage_suggest]} 的 WARN。
     *
     * <p>索引的「需求集合」因此收敛到这一个判断上：<b>要不要建、要不要查、要不要灌词，都问它</b>。
     * 三个调用点共用同一个开关，是避免「一边以为有、另一边以为没有」的唯一办法。
     *
     * <p>注意它只回答"有没有建议字段"，不回答"能灌进多少词"——
     * 后者由 {@code kuma.suggest.max-docs-per-type} 限住，见 {@code SuggestWordServiceImpl}。
     */
    public boolean hasSuggestField() {
        return !suggestFields().isEmpty();
    }

    /** 声明了 {@code suggest=true} 的字段清单（顺序即配置顺序）。 */
    public List<FieldConfig> suggestFields() {
        if (fields == null) {
            return List.of();
        }
        return fields.stream().filter(f -> Boolean.TRUE.equals(f.getSuggest())).toList();
    }

    /**
     * 建议词权重：取所有建议字段里最大的 {@code suggestWeight}。
     *
     * <p>它是 completion suggester 的 {@code weight}，决定同一前缀下多条建议的先后。
     * 取最大值而不是求和：多个字段命中同一个关键词时，用户看到的仍然只是一条建议，
     * 把权重相加会让"建议字段多的类型"天然压过"字段少的类型"，而两者之间本来没有这种可比性。
     *
     * <p>下限取 1：ES 的 completion weight 允许 0，但 0 意味着这条建议在排序上彻底没有分量，
     * 而配置里漏写 {@code suggestWeight} 是很容易发生的事（默认值就是 0），
     * 不该因此让整个类型的建议都排到最后。
     */
    public int suggestWeight() {
        int max = suggestFields().stream()
                .map(FieldConfig::getSuggestWeight)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        return Math.max(1, max);
    }

    public Optional<FieldConfig> field(String name) {
        if (fields == null || name == null) {
            return Optional.empty();
        }
        return fields.stream().filter(f -> name.equals(f.getName())).findFirst();
    }

    /** 字段是否存在 —— 所有来自用户输入的字段名在进入 DSL 前都必须过这一关。 */
    public boolean hasField(String name) {
        return field(name).isPresent();
    }

    public boolean isFacetable(String name) {
        return field(name).map(f -> Boolean.TRUE.equals(f.getFacetable())).orElse(false);
    }

    public boolean isSortable(String name) {
        return field(name).map(f -> Boolean.TRUE.equals(f.getSortable())).orElse(false);
    }

    /**
     * 把「逻辑字段名」翻译成「聚合 / 排序 / 精确过滤语境下真正该打在 ES 上的字段名」。
     *
     * <h3>为什么必须有这个方法</h3>
     * {@code TEXT} 类型在 ES 里默认<b>关闭 doc_values</b>，它只能用于全文检索，
     * 不能直接做 term 过滤、terms 聚合与排序 —— 强行这么写会得到
     * {@code Fielddata is disabled on text fields by default} 运行时错误。
     * 唯一能承载这些操作的是 {@code MappingGenerator} 为每个 TEXT 字段自动生成的
     * {@code .keyword} 子字段。
     *
     * <p>因此所有非全文检索的取值路径都必须经过本方法转换。
     * 这比"让管理员在配置里手写 title.keyword"要好：配置表里写的是业务字段名，
     * 子字段是实现细节，不该泄漏给使用配置的人。
     *
     * <p>反向约束：{@code MappingGenerator#buildTextProperty} 必须<b>始终</b>为 TEXT 字段
     * 生成 {@code .keyword} 子字段，否则本方法会指向一个不存在的字段。
     */
    public String exactField(String name) {
        return field(name)
                .filter(f -> f.getType() == FieldType.TEXT)
                .map(f -> name + ".keyword")
                .orElse(name);
    }
}
