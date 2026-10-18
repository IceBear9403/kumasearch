package com.kuma.search.web.health;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 健康探针。
 *
 * <h3>为什么必须自己写一个</h3>
 * Spring Boot 只为 DataSource、Redis 等有自动配置的组件提供探针。
 * 本项目用的是官方 {@code elasticsearch-java} 客户端而非 Spring Data ES，
 * 因此不会有任何探针 —— {@code /actuator/health} 会在 ES 已经完全不可用时
 * 依然返回 {@code UP}。对监控系统而言这是最糟的情况：它以为一切正常。
 *
 * <h3>状态映射为什么不是简单的"能连上就是 UP"</h3>
 * ES 的集群健康有三个级别：
 * <ul>
 *   <li>{@code green} —— 主分片与副本分片都正常，返回 UP；</li>
 *   <li>{@code yellow} —— 主分片正常、副本未分配。单机部署（replicas=0）时
 *       这是<b>常态</b>，搜索完全可用，因此也返回 UP；</li>
 *   <li>{@code red} —— <b>有主分片未分配</b>，意味着部分数据不可搜索。
 *       此时返回 DOWN：搜索结果会缺数据，这不是"性能下降"而是"结果错误"。</li>
 * </ul>
 *
 * <p>把 yellow 也判为 DOWN 是常见错误 —— 它会让单机部署的健康检查永远显示异常，
 * 运维很快就会学会忽略这个告警，于是 red 也一起被忽略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsHealthIndicator implements HealthIndicator {

    private final ElasticsearchClient client;

    @Override
    public Health health() {
        try {
            HealthResponse response = client.cluster().health(h -> h);

            String status = String.valueOf(response.status());
            Health.Builder builder = "red".equalsIgnoreCase(status)
                    ? Health.down()
                    : Health.up();

            return builder
                    .withDetail("clusterName", response.clusterName())
                    .withDetail("status", status)
                    .withDetail("numberOfNodes", response.numberOfNodes())
                    .withDetail("activeShards", response.activeShards())
                    .withDetail("unassignedShards", response.unassignedShards())
                    .build();
        } catch (Exception e) {
            // 异常信息只保留一句摘要：健康检查的响应可能被暴露给未认证的调用方，
            // 堆栈里可能包含集群地址、认证信息等不该外泄的内容。
            log.warn("ES 健康检查失败: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getClass().getSimpleName())
                    .withDetail("message", e.getMessage())
                    .build();
        }
    }
}
