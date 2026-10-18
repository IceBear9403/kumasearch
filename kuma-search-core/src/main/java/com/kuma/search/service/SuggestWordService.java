package com.kuma.search.service;

import java.util.Map;

/**
 * 联想索引的<b>灌词</b>：把内容里的可联想文本（标题、拼音、首字母）写进
 * {@code kuma_{typeCode}_suggest}。
 *
 * <h3>为什么需要它（这个功能之前是完全缺失的）</h3>
 * 联想索引的创建、查询、重建都有人写，唯独<b>没有任何代码往里写过一条数据</b>。
 * 于是 {@code _cat/indices} 里索引建得好好的，每一次联想请求却都返回空列表 ——
 * 现象是"联想不灵"，而真实原因是"索引里一个字都没有"。
 * 这条链路上，"建好了"与"能用"之间隔着的正是这个接口。
 *
 * <h3>词从哪里来、为什么不是从主索引来</h3>
 * 建议词直接取自内容源表（{@code t_search_content}，且只取 {@code status=1}）：
 * <ul>
 *   <li><b>状态过滤是免费的</b>。主索引里草稿与已驳回的内容同样存在
 *       （写入链路不按状态过滤），从主索引灌词就得自己再过滤一遍，
 *       而漏掉这一步的后果是"未发布内容的标题出现在下拉框里" —— 一种静默的内容泄漏。</li>
 *   <li><b>不依赖主索引的存在</b>。灌词因此可以在主索引重建期间照常工作。</li>
 * </ul>
 *
 * <h3>灌哪些字段由配置决定</h3>
 * 只处理 {@code field_config} 里声明了 {@code suggest=true} 的字段
 * （{@link com.kuma.search.es.mapping.IndexDefinition#hasSuggestField()}）。
 * 这与"要不要建这个索引""要不要查这个索引"共用同一个开关，
 * 三处不一致正是历史上"索引与需求对不上"的来源。
 *
 * <h3>上限</h3>
 * 每个类型最多灌 {@code kuma.suggest.max-docs-per-type} 条（按业务权重取前 N）。
 * completion 的 FST 常驻堆内存，而采集类内容的条数会持续增长 ——
 * 这个上限是安全阀，理由见 {@code KumaSearchProperties.Suggest#maxDocsPerType}。
 *
 * <p>本接口的<b>全部方法都假定联想索引已经存在</b>（由
 * {@code DynamicIndexManager#rebuildSuggestIndex} 或自动建索引任务保证）。
 * 对没有建议字段的类型，所有方法都是空操作。
 */
public interface SuggestWordService {

    /**
     * 全量灌词：把源表里该类型所有已发布内容的建议词写进联想索引。
     *
     * <p>调用前联想索引<b>必须是空的或刚重建过</b>，本方法不做清理 ——
     * 它是"往里灌"而不是"重来一遍"。清空由调用方（重建流程）负责。
     *
     * @return 实际写入的建议文档条数
     */
    int fill(String typeCode);

    /**
     * 增量同步一条内容。
     *
     * <p>按文档里的 {@code status} 自行决定写入还是删除：已发布则写入，
     * 其余状态（草稿、驳回）则<b>删掉</b>它已有的建议词。这一点很重要 ——
     * 内容被驳回/下架之后，如果建议词还留在索引里，用户仍然能从下拉框里
     * 看到这个标题，点下去却搜出零条结果。
     *
     * @param document 与写进主索引的那份文档<b>同一份</b>（字段名一致），
     *                 为 null 或空表示没有可用内容，按删除处理
     */
    void syncOne(String typeCode, String bizId, Map<String, Object> document);

    /** 删除一条内容对应的建议词（内容被物理删除时用）。 */
    void removeOne(String typeCode, String bizId);
}
