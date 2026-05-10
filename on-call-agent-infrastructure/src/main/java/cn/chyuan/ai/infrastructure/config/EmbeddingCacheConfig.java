package cn.chyuan.ai.infrastructure.config;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.infrastructure.gateway.cache.CachedEmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.ProxyBeanMethods;

/**
 * 嵌入缓存配置 — 启用/禁用嵌入向量缓存
 */
@Slf4j
@Configuration
@ProxyBeanMethods(false)
public class EmbeddingCacheConfig {

    @Value("${rag.cache.embedding.enabled:false}")
    private boolean cacheEnabled;

    @Value("${rag.cache.embedding.max-size:10000}")
    private int maxSize;

    @Value("${rag.cache.embedding.expire-hours:24}")
    private int expireHours;

    /**
     * 创建缓存装饰的嵌入服务
     * <p>
     * 当 rag.cache.embedding.enabled=true 时，使用缓存版本
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "rag.cache.embedding.enabled", havingValue = "true")
    public IEmbeddingService cachedEmbeddingService(IEmbeddingService delegate) {
        log.info("启用嵌入向量缓存: maxSize={}, expireHours={}", maxSize, expireHours);
        return new CachedEmbeddingService(delegate, maxSize, expireHours);
    }

}
