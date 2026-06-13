package cn.chyuan.ai.infrastructure.config;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.infrastructure.gateway.cache.TwoLevelEmbeddingCacheService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 二级嵌入缓存配置 — L1 本地 Guava + L2 Redis
 * <p>
 * 启用条件：
 * <ul>
 *   <li>rag.cache.embedding.two-level.enabled=true</li>
 *   <li>embedding.fallback.enabled=false（降级策略未启用）</li>
 * </ul>
 * <p>
 * 当二级缓存启用时，覆盖 EmbeddingCacheConfig 中的单级缓存 Bean。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${rag.cache.embedding.two-level.enabled:false} && !${embedding.fallback.enabled:false}")
public class TwoLevelEmbeddingCacheConfig {

    @Value("${rag.cache.embedding.max-size:10000}")
    private int localMaxSize;

    @Value("${rag.cache.embedding.expire-hours:24}")
    private int localExpireHours;

    @Value("${rag.cache.embedding.model-name:embedding-3}")
    private String modelName;

    @Value("${rag.cache.embedding.two-level.redis-ttl-hours:48}")
    private long redisTtlHours;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /**
     * 创建二级缓存装饰的嵌入服务 — 覆盖单级缓存 Bean
     * <p>
     * L1: 本地 Guava Cache（快速，单实例）
     * L2: Redis（跨实例共享，热点 query 零延迟）
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "rag.cache.embedding.enabled", havingValue = "true")
    public IEmbeddingService twoLevelCachedEmbeddingService(
            IEmbeddingService delegate,
            RedisTemplate<String, String> redisTemplate) {

        log.info("启用二级嵌入缓存（@Primary）: L1 maxSize={}, expireHours={}h, L2 redisTtlHours={}h, modelName={}",
                localMaxSize, localExpireHours, redisTtlHours, modelName);

        return new TwoLevelEmbeddingCacheService(
                delegate,
                localMaxSize,
                localExpireHours,
                modelName,
                redisTemplate,
                redisTtlHours,
                meterRegistry
        );
    }
}
