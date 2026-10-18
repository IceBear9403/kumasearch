package com.kuma.search.es.gateway;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 批量写入的唯一入口。
 *
 * <h3>三个关键设计点</h3>
 * <ol>
 *   <li><b>_id 用业务主键</b>。所有写入都是 upsert 语义（index 操作），
 *       因此同一条数据重复消费不会产生重复文档，这是「至少一次投递」能安全工作的前提。</li>
 *   <li><b>不调用 refresh</b>。写入立即可见与写入吞吐不可兼得；
 *       本项目取吞吐，接受默认 1s 的可见延迟。</li>
 *   <li><b>逐条检查 bulk 响应</b>。ES 的 bulk 接口即使整个请求成功返回，
 *       也可能有个别文档写入失败（如 mapping 冲突），只看 HTTP 状态码会静默丢数据。
 *       这是使用 bulk 时最常见的坑。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsBulkGateway {

    private final ElasticsearchClient client;
    private final ObjectMapper objectMapper;
    private final KumaSearchProperties properties;

    /**
     * 批量 upsert。
     *
     * <h3>⚠️ 单条文档怎么放进请求：只能用 {@code document(...)}，不能用 {@code withJson(...)}</h3>
     * 这是本类唯一一个"看着能编译、运行必炸"的坑，2026-10-16 才被发现（见
     * {@code 08-roadmap.md} 第 30 项），完整写在这里免得再踩：
     *
     * <p>{@code IndexOperation.Builder.withJson(Reader)} 看起来与单条的
     * {@code client.index(i -> i.withJson(...))} 对称，实际<b>完全不支持</b>。
     * 它的实现（{@code WithJsonObjectBuilderBase#withJson}）会拿
     * {@code getClass().getEnclosingClass()} 去查 deserializer —— 对 index 操作就是
     * {@code IndexOperation}，而 <b>bulk 的写操作类型是只写类型</b>（{@code NdJsonpSerializable}，
     * 没有 {@code _DESERIALIZER}），查不到就抛：
     * <pre>
     * java.lang.IllegalArgumentException: Class class co.elastic.clients.elasticsearch.core.bulk.IndexOperation cannot be read from JSON
     * </pre>
     * 关键点是<b>它在拼请求体时就抛，一次 HTTP 都不会发出</b>，与文档内容无关 ——
     * 所以它不会表现为"某几条写不进去"，而是"整批一条都进不去"。
     * 正确写法是 {@code .document(JsonData.fromJson(json))}（或直接 {@code .document(map)}）：
     * 文档由传输层作为 NDJSON 的独立几行写出。
     *
     * @param indexName 物理索引名（不传别名：批量写入需要明确落到哪个版本，
     *                  否则 reindex 期间可能出现半个批次写到旧索引、半个批次写到新索引）
     * @param pkField   业务主键字段名，其值用作 {@code _id}
     * @param documents 文档列表
     * @return 批量写入结果，含成功数与逐条失败明细
     */
    public BulkResult bulkUpsert(String indexName, String pkField, List<Map<String, Object>> documents) {
        if (documents == null || documents.isEmpty()) {
            return BulkResult.builder().successCount(0).failureCount(0).failures(List.of()).build();
        }

        int success = 0;
        List<String> failures = new ArrayList<>();

        // 按 batch size 切分：一次提交过多会让单个请求体过大，
        // 且失败时整批重试的代价高。
        int batchSize = properties.getElasticsearch().getBulkBatchSize();
        for (int i = 0; i < documents.size(); i += batchSize) {
            List<Map<String, Object>> batch = documents.subList(i, Math.min(i + batchSize, documents.size()));

            try {
                BulkRequest.Builder builder = new BulkRequest.Builder();
                for (Map<String, Object> doc : batch) {
                    Object idValue = doc.get(pkField);
                    if (idValue == null) {
                        failures.add("文档缺少业务主键字段 " + pkField + "，已跳过");
                        continue;
                    }
                    String id = String.valueOf(idValue);

                    // 必须用 document(...)：withJson(...) 在 bulk 上必然抛异常，见方法注释。
                    builder.operations(op -> op
                            .index(idx -> idx
                                    .index(indexName)
                                    .id(id)
                                    .document(JsonData.fromJson(toJson(doc)))));
                }

                BulkResponse response = client.bulk(builder.build());
                if (response.errors()) {
                    // 逐条检查：整个请求成功不代表每条都成功。
                    for (BulkResponseItem item : response.items()) {
                        if (item.error() != null) {
                            failures.add(item.id() + ": " + item.error().reason());
                        } else {
                            success++;
                        }
                    }
                } else {
                    success += batch.size();
                }
            } catch (Exception e) {
                // 拼请求与提交都包在这一层里，理由不是"怕网络抖动"，而是：
                // 拼请求体失败（例如上面那种客户端 API 用错）原先会**整个抛出本方法**，
                // 绕过 failures 这条统一出口 —— 调用方看到的是一个未分类的异常，
                // 而不是"这一批没写进去"。塌缩成"整批失败"更好排查，也更难被忽略。
                log.error("批量写入失败，index={}，批次起始={}，原因={}", indexName, i, e.getMessage(), e);
                batch.forEach(d -> failures.add(String.valueOf(d.get(pkField)) + ": " + e.getMessage()));
            }
        }

        if (!failures.isEmpty()) {
            log.warn("批量写入部分失败，index={}，成功={}，失败={}", indexName, success, failures.size());
        }
        return BulkResult.builder()
                .successCount(success)
                .failureCount(failures.size())
                .failures(failures)
                .build();
    }

    /**
     * 单条写入（upsert）。用于管理后台「录入即索引」这类低频路径。
     *
     * <p>这里 {@code withJson} 是<b>可以</b>用的：它作用于 {@code IndexRequest.Builder}，
     * 而 {@code IndexRequest} 是双向类型（有 {@code _DESERIALIZER}），
     * 与 {@link #bulkUpsert} 里那个只写的 {@code IndexOperation} 不同。
     * 两处的写法看着对称、其实不能互相套用，理由见 bulkUpsert 的注释。
     */
    public void upsert(String indexName, String id, Map<String, Object> document) {
        try {
            client.index(i -> i
                    .index(indexName)
                    .id(id)
                    .withJson(new StringReader(toJson(document))));
        } catch (Exception e) {
            throw BizException.of(ResultCode.ES_QUERY_FAILED,
                    "写入文档失败，index=" + indexName + ", id=" + id, e);
        }
    }

    /** 单条删除。删除不存在的 id 在 ES 中不算错误，是幂等的。 */
    public void delete(String indexName, String id) {
        try {
            client.delete(d -> d.index(indexName).id(id));
        } catch (Exception e) {
            throw BizException.of(ResultCode.ES_QUERY_FAILED,
                    "删除文档失败，index=" + indexName + ", id=" + id, e);
        }
    }

    /** 批量删除。 */
    public void bulkDelete(String indexName, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        BulkRequest.Builder builder = new BulkRequest.Builder();
        ids.forEach(id -> builder.operations(op -> op.delete(d -> d.index(indexName).id(id))));
        try {
            client.bulk(builder.build());
        } catch (Exception e) {
            throw BizException.of(ResultCode.ES_QUERY_FAILED,
                    "批量删除失败，index=" + indexName + "，数量=" + ids.size(), e);
        }
    }

    private String toJson(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (Exception e) {
            throw BizException.of(ResultCode.SYSTEM_ERROR, "文档序列化失败", e);
        }
    }

    /**
     * 批量写入结果。
     *
     * <p>刻意返回失败明细而不是一个 boolean：部分失败是常态，
     * 调用方需要据此决定「哪些 id 要重试」以及「是否要告警」。
     */
    @Data
    @Builder
    public static class BulkResult {
        private int successCount;
        private int failureCount;
        /** 失败明细，形如 {@code "docId: reason"}。 */
        private List<String> failures;

        public boolean hasFailure() {
            return failureCount > 0;
        }
    }
}
