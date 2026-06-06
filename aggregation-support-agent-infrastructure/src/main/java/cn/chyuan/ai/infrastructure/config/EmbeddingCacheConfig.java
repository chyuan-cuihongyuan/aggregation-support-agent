package cn.chyuan.ai.infrastructure.config;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.infrastructure.gateway.cache.CachedEmbeddingService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 嵌入缓存配置 — 启用/禁用嵌入向量缓存
 * <p>
 * 仅在降级策略未启用（embedding.fallback.enabled != true）时作为 @Primary。
 * 降级策略启用时，缓存层由 EmbeddingFallbackConfig 内部构建，此处不再创建 Bean。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "embedding.fallback.enabled", havingValue = "false", matchIfMissing = true)
public class EmbeddingCacheConfig {

    @Value("${rag.cache.embedding.max-size}")
    private int maxSize;

    @Value("${rag.cache.embedding.expire-hours}")
    private int expireHours;

    @Value("${rag.cache.embedding.model-name}")
    private String modelName;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /**
     * 创建缓存装饰的嵌入服务 — 作为唯一 @Primary
     * <p>
     * 当降级策略未启用时，缓存层直接包装原始嵌入提供商并标记为 @Primary。
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "rag.cache.embedding.enabled", havingValue = "true")
    public IEmbeddingService cachedEmbeddingService(IEmbeddingService delegate) {
        log.info("启用嵌入向量缓存（@Primary）: maxSize={}, expireHours={}, modelName={}", maxSize, expireHours, modelName);
        return new CachedEmbeddingService(delegate, maxSize, expireHours, modelName, meterRegistry);
    }

}
