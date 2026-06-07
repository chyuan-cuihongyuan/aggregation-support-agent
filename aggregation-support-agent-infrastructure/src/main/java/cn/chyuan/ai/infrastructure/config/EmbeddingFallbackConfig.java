package cn.chyuan.ai.infrastructure.config;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.infrastructure.gateway.BigModelEmbeddingGateway;
import cn.chyuan.ai.infrastructure.gateway.DashScopeEmbeddingGateway;
import cn.chyuan.ai.infrastructure.gateway.FallbackEmbeddingService;
import cn.chyuan.ai.infrastructure.gateway.cache.CachedEmbeddingService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

/**
 * 嵌入服务降级配置 — 主提供商失败时自动切换到备用提供商
 * <p>
 * 当 embedding.fallback.enabled=true 时，此配置类构建完整的嵌入链：
 * <pre>
 *   原始提供商（bigmodel）→ 缓存层（CachedEmbeddingService）→ 降级层（FallbackEmbeddingService）
 * </pre>
 * 同时禁用 EmbeddingCacheConfig 的 @Primary Bean，避免冲突和循环依赖。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "embedding.fallback.enabled", havingValue = "true")
public class EmbeddingFallbackConfig {

    /** 主提供商（BigModel） */
    @Autowired(required = false)
    private BigModelEmbeddingGateway bigModelEmbeddingGateway;

    /** 备用提供商（DashScope） */
    @Autowired(required = false)
    private DashScopeEmbeddingGateway dashScopeEmbeddingGateway;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Value("${embedding.fallback.providers:}")
    private String fallbackProviders;

    @Value("${embedding.fallback.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${embedding.fallback.retry.initial-delay-ms:1000}")
    private long initialRetryDelayMs;

    @Value("${embedding.fallback.retry.multiplier:2.0}")
    private double retryMultiplier;

    @Value("${rag.cache.embedding.max-size:10000}")
    private int cacheMaxSize;

    @Value("${rag.cache.embedding.expire-hours:24}")
    private int cacheExpireHours;

    @Value("${rag.cache.embedding.model-name:embedding-3}")
    private String cacheModelName;

    /**
     * 构建完整的嵌入链：原始提供商 → 缓存 → 降级，作为唯一 @Primary Bean
     * <p>
     * 直接注入 BigModelEmbeddingGateway（具体类型，避免 IEmbeddingService 的循环依赖），
     * 然后依次包装缓存层和降级层。
     */
    @Bean
    @Primary
    public IEmbeddingService fallbackEmbeddingService() {
        log.info("构建嵌入服务降级链: primary={}, fallback={}", getActiveProviderName(), fallbackProviders);

        // 1. 原始提供商作为基础
        IEmbeddingService baseService = bigModelEmbeddingGateway;
        if (baseService == null) {
            throw new IllegalStateException("主嵌入提供商（BigModel）不可用，请检查 embedding.provider 配置");
        }

        // 2. 包装缓存层（直接 new，不走 Spring Bean 依赖，避免循环引用）
        IEmbeddingService cachedService = new CachedEmbeddingService(
                baseService, cacheMaxSize, cacheExpireHours, cacheModelName, meterRegistry);
        log.info("降级链: 缓存层已包装: maxSize={}, expireHours={}", cacheMaxSize, cacheExpireHours);

        // 3. 构建备用提供商列表
        List<IEmbeddingService> fallbackServices = buildFallbackChain();

        // 4. 包装降级层
        FallbackEmbeddingService fallbackService = new FallbackEmbeddingService(
                cachedService, fallbackServices, maxRetryAttempts, initialRetryDelayMs, retryMultiplier);

        log.info("嵌入服务降级链构建完成: 基础提供商={}, 缓存=true, 备用提供商数={}, 重试次数={}",
                getActiveProviderName(), fallbackServices.size(), maxRetryAttempts);

        return fallbackService;
    }

    /**
     * 嵌入降级自动恢复探测器 — 周期性探测主提供商是否恢复，恢复后自动切回。
     * <p>
     * 仅当注入的 @Primary 嵌入服务确为 FallbackEmbeddingService 时才生效。
     */
    @Bean
    public EmbeddingRecoveryProbe embeddingRecoveryProbe(IEmbeddingService fallbackEmbeddingService) {
        return new EmbeddingRecoveryProbe(fallbackEmbeddingService);
    }

    /**
     * 定时探测组件 — 作为 Spring Bean 承载 @Scheduled，调用 FallbackEmbeddingService 的恢复探测
     */
    public static class EmbeddingRecoveryProbe {

        private final FallbackEmbeddingService fallbackService;

        public EmbeddingRecoveryProbe(IEmbeddingService embeddingService) {
            this.fallbackService = embeddingService instanceof FallbackEmbeddingService
                    ? (FallbackEmbeddingService) embeddingService
                    : null;
        }

        /** 每 5 分钟探测一次主提供商是否恢复 */
        @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${embedding.fallback.recovery-probe-ms:300000}")
        public void probe() {
            if (fallbackService != null) {
                fallbackService.attemptRecovery();
            }
        }
    }

    /**
     * 根据配置构建备用提供商链
     */
    private List<IEmbeddingService> buildFallbackChain() {
        if (fallbackProviders == null || fallbackProviders.trim().isEmpty()) {
            log.warn("未配置备用嵌入提供商（embedding.fallback.providers），降级策略将无法生效");
            return List.of();
        }

        List<IEmbeddingService> chain = new ArrayList<>();
        String[] providers = fallbackProviders.split(",");

        for (String provider : providers) {
            String normalized = provider.trim().toLowerCase();
            IEmbeddingService service = resolveProvider(normalized);
            if (service != null) {
                chain.add(service);
                log.info("降级链追加提供商: {} -> {}", normalized, service.getClass().getSimpleName());
            } else {
                log.warn("降级链跳过不可用提供商: {}（未找到对应 Bean）", normalized);
            }
        }

        if (chain.isEmpty()) {
            log.warn("降级链为空，所有备用提供商均不可用");
        }

        return chain;
    }

    /**
     * 根据名称解析提供商 Bean
     */
    private IEmbeddingService resolveProvider(String providerName) {
        return switch (providerName) {
            case "dashscope" -> dashScopeEmbeddingGateway;
            case "bigmodel" -> bigModelEmbeddingGateway;
            default -> {
                log.warn("未知的嵌入提供商: {}", providerName);
                yield null;
            }
        };
    }

    /**
     * 获取当前活跃的提供商名称
     */
    private String getActiveProviderName() {
        if (bigModelEmbeddingGateway != null) return "bigmodel";
        if (dashScopeEmbeddingGateway != null) return "dashscope";
        return "unknown";
    }
}
