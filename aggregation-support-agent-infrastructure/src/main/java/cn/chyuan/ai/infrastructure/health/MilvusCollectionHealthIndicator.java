package cn.chyuan.ai.infrastructure.health;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.KeyValuePair;
import io.milvus.param.R;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.collection.HasCollectionParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 向量库健康指示器（SELFLOOP6 loop-602，工单 0800/0801，池项 G44）。
 * <p>
 * 与 loop-404 ES 指示器同构：自定义 {@link MilvusServiceClient} bean（非 starter
 * 路径）不会被 Boot 自动健康检查覆盖，宕机时 /actuator/health 仍为 UP——此处补齐：
 * 集合存在报 UP 并附行数（num_entities，回答「入库链路是否在写」）；集合缺失/
 * RPC 异常报 DOWN 并附原因。milvus.enabled=false 时不注册，不污染健康聚合。
 * <p>
 * 借鉴来源：Spring Boot Actuator HealthIndicator 官方惯例。
 */
@Slf4j
@Configuration
@ConditionalOnBean(MilvusServiceClient.class)
public class MilvusCollectionHealthIndicator {

    private static final String ROW_COUNT_KEY = "num_entities";

    @Bean
    public HealthIndicator milvusCollectionHealth(MilvusServiceClient milvusServiceClient,
                                                  cn.chyuan.ai.infrastructure.config.MilvusConfigProperties props) {
        return () -> {
            String collection = props.getCollectionName();
            try {
                R<Boolean> hasCollection = milvusServiceClient.hasCollection(
                        HasCollectionParam.newBuilder().withCollectionName(collection).build());
                if (hasCollection.getStatus() != R.Status.Success.getCode() || !Boolean.TRUE.equals(hasCollection.getData())) {
                    return Health.down().withDetail("collection", collection)
                            .withDetail("reason", "集合不存在").build();
                }
                R<GetCollectionStatisticsResponse> stats = milvusServiceClient.getCollectionStatistics(
                        GetCollectionStatisticsParam.newBuilder().withCollectionName(collection).build());
                return toHealth(collection, stats);
            } catch (Exception e) {
                log.warn("Milvus 健康检查异常: {}", e.getMessage());
                return Health.down(new IllegalStateException("Milvus 健康检查失败: " + e.getMessage(), e))
                        .withDetail("collection", collection).build();
            }
        };
    }

    /** 统计响应 → Health 的纯转换（可测）：UP 附行数，RPC 失败转 DOWN */
    static Health toHealth(String collection, R<GetCollectionStatisticsResponse> stats) {
        if (stats == null || stats.getStatus() != R.Status.Success.getCode() || stats.getData() == null) {
            return Health.down().withDetail("collection", collection)
                    .withDetail("reason", "统计查询失败").build();
        }
        String rowCount = stats.getData().getStatsList().stream()
                .filter(kv -> ROW_COUNT_KEY.equals(kv.getKey()))
                .findFirst()
                .map(KeyValuePair::getValue)
                .orElse("unknown");
        return Health.up().withDetail("collection", collection)
                .withDetail("rowCount", rowCount).build();
    }
}
