package cn.chyuan.ai.infrastructure.gateway;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 降级链水位指标绑定测试（SELFLOOP5 loop-504，工单 0706/0707）。
 * 以 SimpleMeterRegistry 验证 Gauge 绑定值随计数器变化。
 */
class FallbackMetricsBindingTest {

    private static class StubEmbedding implements IEmbeddingService {
        @Override public float[] embed(String text) { return new float[0]; }
        @Override public List<float[]> embedBatch(List<String> texts) { return List.of(); }
        @Override public int dimension() { return 1024; }
    }

    @Test
    @DisplayName("Gauge 绑定值随降级计数变化")
    void gaugesFollowCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IEmbeddingService primary = new StubEmbedding();
        FallbackEmbeddingService service = new FallbackEmbeddingService(
                primary, List.of(new StubEmbedding()), 3, 1, 2.0);

        Gauge.builder("embedding_fallback_total", service,
                FallbackEmbeddingService::getFallbackCount).register(registry);
        Gauge.builder("embedding_retry_success_total", service,
                FallbackEmbeddingService::getRetrySuccessCount).register(registry);

        assertEquals(0.0, registry.get("embedding_fallback_total").gauge().value());
        // 反射置数验证绑定链路（字段 volatile，直接写不触发业务路径）
        try {
            var f = FallbackEmbeddingService.class.getDeclaredField("fallbackCount");
            f.setAccessible(true);
            f.setInt(service, 3);
        } catch (Exception ignored) {
        }
        assertEquals(3.0, registry.get("embedding_fallback_total").gauge().value());
    }
}
