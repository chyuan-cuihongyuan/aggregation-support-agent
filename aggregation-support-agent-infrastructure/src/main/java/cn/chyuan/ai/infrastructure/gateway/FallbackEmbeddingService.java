package cn.chyuan.ai.infrastructure.gateway;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.types.exception.EmbeddingException;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * 降级嵌入服务 — 主提供商失败时自动切换到备用提供商
 * <p>
 * 降级策略：
 * <ul>
 *   <li>主提供商调用失败时，根据异常类型决策：可重试错误先重试，不可重试直接降级</li>
 *   <li>降级到备用提供商列表（按优先级排序），逐个尝试</li>
 *   <li>所有提供商都失败时，抛出最后一个异常</li>
 *   <li>降级成功后自动记录，后续请求优先使用降级后的提供商（避免重复失败）</li>
 * </ul>
 * <p>
 * 配置示例（application-dev.yml）：
 * <pre>
 * embedding:
 *   provider: bigmodel
 *   fallback:
 *     enabled: true
 *     providers: dashscope,deepseek
 *     retry:
 *       max-attempts: 3
 *       initial-delay-ms: 1000
 *       multiplier: 2.0
 * </pre>
 */
@Slf4j
public class FallbackEmbeddingService implements IEmbeddingService {

    /** 主嵌入服务 */
    private final IEmbeddingService primary;

    /** 备用嵌入服务列表（按优先级排序） */
    private final List<IEmbeddingService> fallbacks;

    /** 最大重试次数（针对可重试错误） */
    private final int maxRetryAttempts;

    /** 重试初始延迟（毫秒） */
    private final long initialRetryDelayMs;

    /** 重试延迟倍数（指数退避） */
    private final double retryMultiplier;

    /** 当前活跃的嵌入服务（降级后自动切换） */
    private volatile IEmbeddingService activeService;

    /** 降级发生次数（用于监控） */
    private volatile int fallbackCount = 0;

    /** 重试成功次数 */
    private volatile int retrySuccessCount = 0;

    public FallbackEmbeddingService(IEmbeddingService primary,
                                    List<IEmbeddingService> fallbacks,
                                    int maxRetryAttempts,
                                    long initialRetryDelayMs,
                                    double retryMultiplier) {
        this.primary = primary;
        this.fallbacks = fallbacks != null ? fallbacks : Collections.emptyList();
        this.maxRetryAttempts = maxRetryAttempts;
        this.initialRetryDelayMs = initialRetryDelayMs;
        this.retryMultiplier = retryMultiplier;
        this.activeService = primary;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new float[0];
        }

        List<float[]> results = embedBatch(Collections.singletonList(text));
        if (results.isEmpty()) {
            log.error("单文本嵌入结果为空（所有提供商均失败）");
            return new float[0];
        }
        return results.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 尝试当前活跃服务（可能已经降级过）
        Exception caught = null;
        try {
            List<float[]> result = activeService.embedBatch(texts);
            log.debug("嵌入成功: provider={}", getProviderName(activeService));
            return result;
        } catch (Exception e) {
            caught = e;
            log.warn("活跃嵌入服务调用失败: provider={}, error={}", getProviderName(activeService), e.getMessage());
        }

        // 2. 分类异常，决策是否重试
        EmbeddingException lastException = classifyException(caught);

        // 3. 可重试错误先重试（指数退避）
        if (lastException.isRetryable()) {
            List<float[]> retryResult = retryWithBackoff(texts);
            if (retryResult != null) {
                return retryResult;
            }
        }

        // 4. 重试失败或不可重试，尝试降级到备用提供商
        if (!fallbacks.isEmpty()) {
            List<float[]> fallbackResult = tryFallbacks(texts, activeService);
            if (fallbackResult != null) {
                return fallbackResult;
            }
        }

        // 5. 所有提供商都失败
        log.error("所有嵌入服务均失败: primary={}, fallbackCount={}", getProviderName(primary), fallbacks.size());
        throw lastException;
    }

    /**
     * 指数退避重试
     */
    private List<float[]> retryWithBackoff(List<String> texts) {
        long delay = initialRetryDelayMs;
        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                log.info("嵌入服务重试: provider={}, attempt={}/{}", getProviderName(activeService), attempt, maxRetryAttempts);
                Thread.sleep(delay);

                List<float[]> result = activeService.embedBatch(texts);
                retrySuccessCount++;
                log.info("嵌入服务重试成功: provider={}, attempt={}", getProviderName(activeService), attempt);
                return result;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("嵌入服务重试被中断");
                return null;
            } catch (Exception retryEx) {
                log.warn("嵌入服务重试失败: provider={}, attempt={}, error={}",
                        getProviderName(activeService), attempt, retryEx.getMessage());
                delay = (long) (delay * retryMultiplier);
            }
        }
        log.warn("嵌入服务重试耗尽: provider={}, maxAttempts={}", getProviderName(activeService), maxRetryAttempts);
        return null;
    }

    /**
     * 尝试所有备用提供商
     */
    private List<float[]> tryFallbacks(List<String> texts, IEmbeddingService excludeService) {
        // 获取主提供商的维度，用于校验备用提供商兼容性
        int primaryDimension = primary.dimension();

        for (IEmbeddingService fallback : fallbacks) {
            if (fallback == excludeService) {
                continue;
            }
            String providerName = getProviderName(fallback);

            // 维度校验：备用提供商维度必须与主提供商一致，否则跳过
            int fallbackDimension = fallback.dimension();
            if (primaryDimension > 0 && fallbackDimension > 0 && primaryDimension != fallbackDimension) {
                log.warn("嵌入服务降级跳过: {} 维度({})与主提供商维度({})不兼容",
                        providerName, fallbackDimension, primaryDimension);
                continue;
            }

            try {
                log.info("嵌入服务降级: 从 {} 切换到 {}", getProviderName(activeService), providerName);
                List<float[]> result = fallback.embedBatch(texts);

                // 降级成功，切换活跃服务
                activeService = fallback;
                fallbackCount++;
                log.info("嵌入服务降级成功: 切换到 {}, 累计降级次数={}", providerName, fallbackCount);
                return result;
            } catch (Exception e) {
                log.warn("嵌入服务降级失败: fallback={}, error={}", providerName, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 将异常分类为 EmbeddingException
     */
    private EmbeddingException classifyException(Exception e) {
        if (e instanceof EmbeddingException) {
            return (EmbeddingException) e;
        }
        if (e.getCause() instanceof EmbeddingException) {
            return (EmbeddingException) e.getCause();
        }
        // 网络异常
        if (e instanceof java.io.IOException || e.getCause() instanceof java.io.IOException) {
            return new EmbeddingException(
                    EmbeddingException.FailureType.NETWORK_ERROR, getProviderName(activeService), e.getMessage(), e);
        }
        // 其他未知异常
        return new EmbeddingException(
                EmbeddingException.FailureType.UNKNOWN, getProviderName(activeService), e.getMessage(), e);
    }

    /**
     * 获取提供商名称（用于日志）
     */
    private String getProviderName(IEmbeddingService service) {
        if (service == null) return "null";
        String className = service.getClass().getSimpleName();
        if (className.contains("BigModel")) return "bigmodel";
        if (className.contains("DashScope")) return "dashscope";
        if (className.contains("DeepSeek")) return "deepseek";
        if (className.contains("Cached")) return "cached";
        if (className.contains("Fallback")) return "fallback";
        return className;
    }

    /**
     * 重置活跃服务为主服务（用于定期恢复）
     */
    public void resetToPrimary() {
        if (activeService != primary) {
            log.info("嵌入服务恢复为主提供商: {}", getProviderName(primary));
            activeService = primary;
        }
    }

    /**
     * 探测主提供商是否已恢复 — 仅在当前处于降级状态时执行。
     * <p>
     * 用一条轻量探测文本调用主提供商，成功则切回主提供商，避免长期停留在备用提供商上。
     * 由外部定时任务周期性调用。
     *
     * @return 探测后是否处于主提供商状态
     */
    public boolean attemptRecovery() {
        // 未降级，无需探测
        if (activeService == primary) {
            return true;
        }

        try {
            float[] probe = primary.embed("health check");
            if (probe != null && probe.length > 0) {
                log.info("主嵌入提供商探测成功，恢复为主提供商: {}", getProviderName(primary));
                activeService = primary;
                return true;
            }
            log.debug("主嵌入提供商探测返回空向量，保持降级状态");
        } catch (Exception e) {
            log.debug("主嵌入提供商探测失败，保持降级状态: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 获取降级统计信息
     */
    public FallbackStats getStats() {
        return FallbackStats.builder()
                .activeProvider(getProviderName(activeService))
                .primaryProvider(getProviderName(primary))
                .fallbackProviderCount(fallbacks.size())
                .fallbackCount(fallbackCount)
                .retrySuccessCount(retrySuccessCount)
                .degraded(activeService != primary)
                .build();
    }

    /**
     * 降级统计信息
     */
    @lombok.Data
    @lombok.Builder
    public static class FallbackStats {
        private String activeProvider;
        private String primaryProvider;
        private int fallbackProviderCount;
        private int fallbackCount;
        private int retrySuccessCount;
        private boolean degraded;  // 是否已降级（活跃服务非主服务）

        @Override
        public String toString() {
            return String.format("FallbackStats{active=%s, primary=%s, degraded=%s, fallbacks=%d, fallbackCount=%d, retrySuccess=%d}",
                    activeProvider, primaryProvider, degraded, fallbackProviderCount, fallbackCount, retrySuccessCount);
        }
    }
}
