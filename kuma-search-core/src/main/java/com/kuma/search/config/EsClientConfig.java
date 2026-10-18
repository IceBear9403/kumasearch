package com.kuma.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.NodeSelector;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.List;

/**
 * Elasticsearch 客户端装配。
 *
 * <p>这里只做<b>一件事</b>：把官方 Java API Client 的传输层搭起来，并把所有
 * HTTP 层参数（超时、认证、多节点）钉死在这一处。
 * 业务代码永远不直接拿 {@code ElasticsearchClient}，而是经过 {@code EsSearchGateway} /
 * {@code EsIndexGateway} / {@code EsBulkGateway} —— 这样将来升 ES 9 换 transport、
 * 或加节点/换连接池时，改动面被限制在本类与三个网关内。
 *
 * <p>刻意<b>不使用</b> {@code spring-boot-starter-data-elasticsearch}：
 * 本项目的索引是配置驱动的动态多类型索引，SDE 的实体绑定与 Repository 抽象在此场景下只是负担，
 * 且 SDE 只支持 ES 8.x，会锁死未来的升级路径。
 *
 * <p>版本说明：{@code elasticsearch-java} 与 {@code elasticsearch-rest-client} 的版本
 * 由 Spring Boot 3.5.16 的 BOM 统一管理为 8.18.8，与 ES 服务端 8.18.x 对齐，
 * 因此本项目不在 POM 里重复声明版本号。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EsClientConfig {

    private final KumaSearchProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 低层 REST 客户端。多节点时客户端会自动做节点轮询与故障摘除。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RestClient.class)
    public RestClient esRestClient() {
        KumaSearchProperties.Elasticsearch es = properties.getElasticsearch();

        HttpHost[] hosts = es.getUris().stream()
                .map(this::toHttpHost)
                .toArray(HttpHost[]::new);

        RestClientBuilder builder = RestClient.builder(hosts);

        builder.setRequestConfigCallback(requestConfig -> requestConfig
                .setConnectTimeout(es.getConnectTimeout())
                .setSocketTimeout(es.getSocketTimeout()));

        // 跳过专用 master 节点：把检索请求只发给能处理读写的数据节点，避免协调压力打到 master 上。
        // 注意 NodeSelector 是 org.elasticsearch.client 下的独立接口，不是 RestClientBuilder 的嵌套类型。
        builder.setNodeSelector(NodeSelector.SKIP_DEDICATED_MASTERS);

        if (es.getUsername() != null && !es.getUsername().isBlank()) {
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(es.getUsername(), es.getPassword()));
            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        }

        log.info("初始化 Elasticsearch REST 客户端，节点={}，connectTimeout={}ms，socketTimeout={}ms",
                es.getUris(), es.getConnectTimeout(), es.getSocketTimeout());
        return builder.build();
    }

    /**
     * Java API Client 的传输层。
     *
     * <p>复用 Spring 的 {@link ObjectMapper} 而非新建一个：ES 文档的序列化规则
     * （日期格式、空值策略）必须与接口层保持一致，否则会出现
     * 「写入时是 yyyy-MM-dd HH:mm:ss、反序列化时按时间戳解析」这类跨层不一致。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ElasticsearchTransport.class)
    public ElasticsearchTransport esTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));
    }

    @Bean
    @ConditionalOnMissingBean(ElasticsearchClient.class)
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }

    private HttpHost toHttpHost(String uri) {
        URI parsed = URI.create(uri);
        int port = parsed.getPort() > 0 ? parsed.getPort() : ("https".equals(parsed.getScheme()) ? 443 : 9200);
        return new HttpHost(parsed.getHost(), port, parsed.getScheme());
    }
}
