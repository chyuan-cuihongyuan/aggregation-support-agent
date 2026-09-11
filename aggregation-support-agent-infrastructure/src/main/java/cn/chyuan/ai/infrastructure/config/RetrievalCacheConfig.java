package cn.chyuan.ai.infrastructure.config;

import cn.chyuan.ai.domain.rag.service.cache.RetrievalCacheService;
import cn.chyuan.ai.domain.rag.service.query.RuleBasedQueryRewriter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

/**
 * 检索结果缓存条件装配（工单 0167，W5）
 * <p>
 * 开关 {@code rag.cache-enabled}（默认关）：关闭时不装配缓存服务，
 * 编排层每次真实检索（零回归）。开启后按 rag.cache-ttl-seconds（默认 300s）
 * 与 rag.cache-max-capacity（默认 1000）构建 LRU/TTL 缓存，并把
 * retrieval_cache_hit_total / retrieval_cache_miss_total Gauge 绑定到
 * 缓存服务的命中/未命中计数（CachedEmbeddingService 同口径：单一计数源）。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rag.cache-enabled", havingValue = "true")
public class RetrievalCacheConfig {

    /**
     * 检索缓存服务 — 复用 W3 停用词表（英文停用词 + 中文停用词 + 停用字三表合一）
     */
    @Bean
    public RetrievalCacheService retrievalCacheService(
            @Value("${rag.cache-ttl-seconds:300}") long ttlSeconds,
            @Value("${rag.cache-max-capacity:1000}") int maxCapacity,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {

        Set<String> stopwords = new HashSet<>(RuleBasedQueryRewriter.DEFAULT_ENGLISH_STOPWORDS);
        stopwords.addAll(RuleBasedQueryRewriter.DEFAULT_CHINESE_STOPWORDS);
        stopwords.addAll(RuleBasedQueryRewriter.DEFAULT_CHINESE_STOP_CHARS);

        RetrievalCacheService cacheService = new RetrievalCacheService(
                maxCapacity, ttlSeconds, Clock.systemUTC(), stopwords);

        // 指标绑定（可选：无 MeterRegistry 环境不阻断装配）
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            Gauge.builder("retrieval_cache_hit_total", cacheService, RetrievalCacheService::hitCount)
                    .description("检索缓存命中次数")
                    .register(registry);
            Gauge.builder("retrieval_cache_miss_total", cacheService, RetrievalCacheService::missCount)
                    .description("检索缓存未命中次数")
                    .register(registry);
            log.info("检索缓存指标已绑定: retrieval_cache_hit_total / retrieval_cache_miss_total");
        }

        log.info("装配检索结果缓存: ttlSeconds={}, maxCapacity={}", ttlSeconds, maxCapacity);
        return cacheService;
    }
}
