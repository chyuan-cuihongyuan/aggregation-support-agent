package cn.chyuan.ai.config;

import cn.chyuan.ai.infrastructure.gateway.FallbackEmbeddingService;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * embedding 降级计数指标绑定（SELFLOOP6 loop-606，工单 0808/0809，池项 O48）。
 * <p>
 * {@link FallbackEmbeddingService} 的 fallbackCount / retrySuccessCount 此前只有
 * volatile 字段与 getter（loop-414 判据审计点名「未见指标绑定」）——降级风暴在
 * Grafana 不可见。两计数单调递增，按 Micrometer 惯例绑 FunctionCounter
 * （loop-407 rejected_total 同构）。
 */
@Configuration
@ConditionalOnBean(FallbackEmbeddingService.class)
public class EmbeddingFallbackMetricsBinder {

    @Resource
    private MeterRegistry meterRegistry;

    @Resource
    private FallbackEmbeddingService fallbackEmbeddingService;

    @PostConstruct
    public void bind() {
        FunctionCounter.builder("embedding_fallback_total", fallbackEmbeddingService,
                        FallbackEmbeddingService::getFallbackCount)
                .description("embedding 主提供商失败触发降级的累计次数")
                .register(meterRegistry);
        FunctionCounter.builder("embedding_retry_success_total", fallbackEmbeddingService,
                        FallbackEmbeddingService::getRetrySuccessCount)
                .description("embedding 降级后重试成功的累计次数")
                .register(meterRegistry);
    }
}
