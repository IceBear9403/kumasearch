package com.kuma.search.es.gateway;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuma.search.es.mapping.MappingGenerator;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.Collections;
import java.util.List;

/**
 * 索引生命周期的唯一入口：建索引、改 mapping、切别名、删索引、查状态。
 *
 * <p>把索引管理从检索里拆出来，是因为两者的<b>失败语义完全不同</b>：
 * 检索失败只影响一次请求，索引管理失败可能让整个类型不可用。
 * 混在一个类里，会让人用处理检索异常的方式去处理索引操作，后果很难看。
 *
 * <p>本类中的所有写操作都要求调用方（{@code DynamicIndexManager}）已持有分布式锁，
 * 本类自身不做并发控制 —— 这是有意的职责划分：网关只管「和 ES 说话」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsIndexGateway {

    private final ElasticsearchClient client;
    private final ObjectMapper objectMapper;

    /**
     * 按 JSON 请求体创建索引。
     *
     * <p>请求体由 {@code MappingGenerator} 生成，包含 aliases + settings + mappings 三部分。
     * 用 {@code withJson} 而不是逐字段构造 Builder，是因为 mapping 的内容由运行期配置决定，
     * 用 Builder 表达会退化成「把 JSON 翻译成 Builder」的机械劳动，反而更易错。
     */
    public void createIndex(String indexName, String bodyJson) {
        try {
            client.indices().create(c -> c.index(indexName).withJson(new StringReader(bodyJson)));
            log.info("创建索引成功: {}", indexName);
        } catch (Exception e) {
            throw BizException.of(ResultCode.INDEX_NOT_READY,
                    "创建索引失败: " + indexName + "，原因: " + e.getMessage()
                            + analysisResourceHint(e), e);
        }
    }

    /**
     * 「ES 节点上的文件读不到」这一类失败的追加提示。
     *
     * <p>单独识别它，是因为这类失败的 <b>处置动作不在本系统里</b>：mapping 里引用的
     * {@code synonyms_path} 由 ES 按<b>它自己的 config 目录</b>解析（报错里显示为绝对路径），
     * 文件缺失时的原始报错是 {@code IOException while reading synonyms_path: /etc/elasticsearch/...}。
     * 它第一次出现的地方通常是 {@code IndexBootstrapJob} 的定时日志 ——
     * 看起来像"ES 坏了"，而实际要做的事是去另一个系统上建一个文件。
     * 没有这一句，排查会从错误的方向开始（这与 {@code MappingGenerator} 里把
     * {@code _meta.ikDictHint} 写进索引是同一个用意：把"要人去节点上做的事"留在能看见的地方）。
     *
     * <p>识别方式是匹配 {@code while reading} —— ES 读任何分析器资源文件失败都是这个措辞。
     * 匹配不上时返回空串，不改变原有的报错内容。
     */
    private String analysisResourceHint(Exception e) {
        String message = e.getMessage();
        if (message == null || !message.contains("while reading")) {
            return "";
        }
        return "（这不是 ES 故障：mapping 引用的分析器资源文件在 ES 节点上不存在或读不到，"
                + "路径相对于该节点的 config 目录，需在该节点创建并让 ES 进程可读；"
                + "前置条件清单见 docs/07-deployment.md §3.3）";
    }

    /** 索引是否已存在。 */
    public boolean exists(String indexName) {
        try {
            return client.indices().exists(e -> e.index(indexName)).value();
        } catch (Exception e) {
            throw BizException.of(ResultCode.ES_UNAVAILABLE,
                    "检查索引是否存在失败: " + indexName, e);
        }
    }

    /**
     * 更新已有索引的 mapping。
     *
     * <p><b>限制</b>：ES 只允许新增字段，不能修改已存在字段的类型。
     * 想改类型必须走 {@code DynamicIndexManager#rebuildIndex} 重建索引 ——
     * 这是让「索引重建」成为一等公民能力的根本原因，不是可选项。
     */
    public void putMapping(String indexName, ObjectNode mapping) {
        try {
            String json = objectMapper.writeValueAsString(mapping);
            client.indices().putMapping(p -> p.index(indexName).withJson(new StringReader(json)));
            log.info("更新 mapping 成功: {}", indexName);
        } catch (Exception e) {
            throw BizException.of(ResultCode.INDEX_NOT_READY,
                    "更新 mapping 失败: " + indexName + "，原因: " + e.getMessage(), e);
        }
    }

    /**
     * 原子切换别名指向。
     *
     * <p>这是 reindex 不停机的核心动作：一个 update_aliases 请求里同时完成
     * 「移除旧索引的别名」和「把别名加到新索引上」，对查询方是原子的 ——
     * 不存在「别名短暂消失导致查询报 index_not_found」的窗口。
     *
     * <p>用 remove + add 组合而不是先把新索引挂上别名再摘旧的：
     * 后者会让别名在一段时间内同时指向两个索引，查询会同时命中新旧两份数据，
     * 出现重复结果。
     *
     * @param alias    别名
     * @param newIndex 新的物理索引名
     * @param oldIndex 旧的物理索引名，为 null 表示首次创建（只做 add）
     */
    public void switchAlias(String alias, String newIndex, String oldIndex) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode actions = root.putArray("actions");

        if (oldIndex != null && !oldIndex.isBlank()) {
            ObjectNode remove = actions.addObject();
            remove.putObject("remove")
                    .put("index", oldIndex)
                    .put("alias", alias);
        }

        ObjectNode add = actions.addObject();
        add.putObject("add")
                .put("index", newIndex)
                .put("alias", alias)
                // 显式指定写索引：新旧索引同时挂别名期间，写入只会落到这一个上，
                // 避免出现「两个索引都收到部分写入」导致的数据分裂。
                .put("is_write_index", true);

        try {
            String json = objectMapper.writeValueAsString(root);
            client.indices().updateAliases(u -> u.withJson(new StringReader(json)));
            log.info("别名切换成功: {} -> {}（原索引: {}）", alias, newIndex, oldIndex);
        } catch (Exception e) {
            throw BizException.of(ResultCode.INDEX_ALIAS_SWITCH_FAILED,
                    "别名切换失败: " + alias + " -> " + newIndex + "，原因: " + e.getMessage(), e);
        }
    }

    /** 删除索引。用于 reindex 完成后清理旧版本；调用方需自行确认数据已迁移完毕。 */
    public void deleteIndex(String indexName) {
        try {
            client.indices().delete(d -> d.index(indexName));
            log.warn("已删除索引: {}", indexName);
        } catch (Exception e) {
            throw BizException.of(ResultCode.INDEX_NOT_READY,
                    "删除索引失败: " + indexName + "，原因: " + e.getMessage(), e);
        }
    }

    /** 统计索引文档数。对账任务与「reindex 后校验」都依赖它。 */
    public long countDocuments(String indexName) {
        try {
            return client.count(c -> c.index(indexName)).count();
        } catch (Exception e) {
            throw BizException.of(ResultCode.ES_UNAVAILABLE,
                    "统计文档数失败: " + indexName, e);
        }
    }

    /**
     * 刷新索引，让最近的写入立即可见。
     *
     * <p><b>只在特殊场景调用</b>（例如 reindex 完成后校验文档数、或管理后台
     * 「录入后立即预览」）。绝不要在每次写入后调用 —— 那等价于把 refresh_interval
     * 设成 0，会让写入吞吐断崖式下跌。
     */
    public void refresh(String indexName) {
        try {
            client.indices().refresh(r -> r.index(indexName));
        } catch (Exception e) {
            log.warn("刷新索引失败: {}，原因={}", indexName, e.getMessage());
        }
    }

    /** 列出某个前缀下的全部物理索引，用于查找历史版本与孤儿索引。 */
    public List<String> listIndices(String prefix) {
        try {
            return client.indices().get(g -> g.index(prefix + "*")).result().keySet().stream()
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.warn("枚举索引失败，prefix={}，原因={}", prefix, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 重新加载同义词与停用词。
     *
     * <p><b>重要限制</b>：热更新只影响之后写入的文档，已索引的文档不会重新分析。
     * 也就是说新增同义词后，历史文档仍然搜不到新词 —— 必须重建索引才能让词典变更
     * 对存量数据生效。这一点在 {@code docs/07-deployment.md} 里有专门说明，
     * 也是常见误解所在。
     */
    public void reloadSearchAnalyzers(String indexName) {
        try {
            client.indices().reloadSearchAnalyzers(r -> r.index(indexName));
            log.info("已重载索引 {} 的 search analyzer（同义词/停用词）", indexName);
        } catch (Exception e) {
            log.warn("重载 search analyzer 失败: {}，原因={}", indexName, e.getMessage());
        }
    }

    /**
     * 用<b>索引上定义的</b>拼音分析器把一段文本转成拼音串，用于灌词。
     *
     * <h3>为什么是"调用 ES"而不是"在 JVM 里算拼音"</h3>
     * 集群已经装了 {@code analysis-pinyin}（主索引的拼音子字段依赖它），
     * 在 Java 侧再引一个拼音库就会有两套规则，同一份数据在两个功能里表现不一致。
     * 详见 {@link com.kuma.search.es.mapping.MappingGenerator#ANALYZER_SUGGEST_PINYIN}。
     *
     * <h3>为什么必须带 index 参数</h3>
     * 自定义分析器住在<b>索引的 settings</b> 里，集群级的分析器只能改
     * {@code elasticsearch.yml} 并重启节点。{@code _analyze} 只有带上 {@code index}
     * 才能解析到 {@code kuma_suggest_pinyin}。
     * 因此本方法有前置条件：<b>联想索引已存在</b>（它由建索引流程保证，
     * 灌词永远发生在建好索引之后）。
     *
     * <h3>失败为什么只降级不抛出</h3>
     * 拼音是"锦上添花"：失败了仍然有中文标题可以联想，而抛出去会让整批灌词中断 ——
     * 一批 5000 条里有一条分析失败就全都不灌，代价远大于收益。
     * 因此这里记 WARN 并把"怎么修"写进日志：最常见的原因是联想索引还是旧的
     * （分析器是在 settings 里，只能靠重建索引应用，{@code PUT _mapping} 改不了它）。
     *
     * @param indexName 联想索引名（分析器定义在它上面）
     * @param text      待转换的文本，通常是内容标题
     * @return 拼音 token 列表（连写全拼、首字母各一条）；失败或无中文时返回空列表
     */
    public List<String> analyzePinyinTokens(String indexName, String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        try {
            AnalyzeResponse response = client.indices().analyze(a -> a
                    .index(indexName)
                    .analyzer(MappingGenerator.ANALYZER_SUGGEST_PINYIN)
                    .text(text));
            return response.tokens().stream()
                    .map(AnalyzeToken::token)
                    .filter(t -> t != null && !t.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.warn("拼音分析失败，本条内容将只灌入原文（无拼音联想能力）: index={}，text={}，原因={}。"
                            + "若原因是 analyzer [{}] 不存在，说明联想索引是旧版本建的（分析器在 settings 里，"
                            + "只能重建索引才能应用）：POST /api/admin/index-type/{{typeCode}}/rebuild-suggest",
                    indexName, text, e.getMessage(), MappingGenerator.ANALYZER_SUGGEST_PINYIN);
            return Collections.emptyList();
        }
    }
}
