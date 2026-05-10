package cn.chyuan.ai.infrastructure.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch配置 — 用于BM25全文检索
 * <p>
 * 当 elasticsearch.enabled=true 时启用
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "elasticsearch")
@ConditionalOnProperty(name = "elasticsearch.enabled", havingValue = "true", matchIfMissing = false)
public class ElasticsearchConfig {

    /** ES主机地址 */
    private String host = "localhost";

    /** ES端口 */
    private int port = 9200;

    /** 索引名称 */
    private String indexName = "rag-documents";

    /**
     * 创建Elasticsearch客户端
     */
    @Bean
    public ElasticsearchClient elasticsearchClient() {
        log.info("初始化Elasticsearch客户端: {}:{}", host, port);

        RestClient restClient = RestClient.builder(
                new HttpHost(host, port)
        ).build();

        RestClientTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper()
        );

        return new ElasticsearchClient(transport);
    }

}
