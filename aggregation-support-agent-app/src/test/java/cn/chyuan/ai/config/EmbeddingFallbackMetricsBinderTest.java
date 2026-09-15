package cn.chyuan.ai.config;

import cn.chyuan.ai.infrastructure.gateway.FallbackEmbeddingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EmbeddingFallbackMetricsBinder 单测（SELFLOOP6 loop-606，工单 0808/0809）。
 * SimpleMeterRegistry + 反射设计数，不 mock micrometer。
 */
class EmbeddingFallbackMetricsBinderTest {

    private SimpleMeterRegistry registry;
    private FallbackEmbeddingService service;
    private EmbeddingFallbackMetricsBinder binder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new FallbackEmbeddingService(null, null, 0, 0, 0);
        binder = new EmbeddingFallbackMetricsBinder();
        ReflectionTestUtils.setField(binder, "meterRegistry", registry);
        ReflectionTestUtils.setField(binder, "fallbackEmbeddingService", service);
    }

    @Test
    @DisplayName("绑定后两 FunctionCounter 可见且随底层计数同步")
    void countersBoundAndSynced() {
        binder.bind();
        ReflectionTestUtils.setField(service, "fallbackCount", 3);
        ReflectionTestUtils.setField(service, "retrySuccessCount", 2);

        Double fallback = registry.get("embedding_fallback_total").functionCounter().count();
        Double retrySuccess = registry.get("embedding_retry_success_total").functionCounter().count();
        assertThat(fallback).isEqualTo(3.0);
        assertThat(retrySuccess).isEqualTo(2.0);
    }

    @Test
    @DisplayName("description 元数据就位（Grafana 可读）")
    void descriptionsPresent() {
        binder.bind();
        assertThat(registry.get("embedding_fallback_total").functionCounter().getId().getDescription()).isNotNull();
        assertThat(registry.get("embedding_retry_success_total").functionCounter().getId().getDescription()).isNotNull();
    }
}
