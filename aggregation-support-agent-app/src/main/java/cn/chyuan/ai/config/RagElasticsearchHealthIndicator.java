package cn.chyuan.ai.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 关键依赖 Elasticsearch 健康指示器（SELFLOOP4 loop-404，工单 0606/0607）。
 * <p>
 * 本仓 ES 为自定义 {@link ElasticsearchClient} bean（非 data-elasticsearch starter 路径），
 * Spring Boot 不会自动配置其健康检查，宕机时 /actuator/health 仍为 UP——此处补齐：
 * ping 成功报 UP 并附耗时；不可达/异常报 DOWN 并附原因。
 * ES 关闭（elasticsearch.enabled=false）时不注册，不污染健康聚合。
 * <p>
 * 借鉴来源：Spring Boot Actuator HealthIndicator 官方惯例。
 */
@Configuration
@ConditionalOnBean(ElasticsearchClient.class)
public class RagElasticsearchHealthIndicator {

    @Bean
    public HealthIndicator ragElasticsearchHealth(ElasticsearchClient elasticsearchClient) {
        return () -> {
            long start = System.currentTimeMillis();
            try {
                boolean reachable = elasticsearchClient.ping().value();
                long elapsed = System.currentTimeMillis() - start;
                if (reachable) {
                    return Health.up().withDetail("ping", "ok")
                            .withDetail("elapsedMs", elapsed).build();
                }
                return Health.down().withDetail("ping", "cluster unreachable")
                        .withDetail("elapsedMs", elapsed).build();
            } catch (Exception e) {
                return Health.down(new IllegalStateException("ES ping 失败: " + e.getMessage(), e))
                        .withDetail("elapsedMs", System.currentTimeMillis() - start).build();
            }
        };
    }
}
