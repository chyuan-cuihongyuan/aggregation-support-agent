package cn.chyuan.ai.infrastructure.config;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.cache.RetrievalCacheService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索缓存装配与指标绑定单测（工单 0167）
 * <p>
 * 覆盖验收：hit/miss 指标断言（retrieval_cache_hit_total / retrieval_cache_miss_total
 * Gauge 绑定到缓存服务单一计数源）+ 无 MeterRegistry 环境装配不阻断
 */
class RetrievalCacheConfigTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    @Test
    void gaugesTrackHitAndMissCounters() {
        RetrievalCacheConfig config = new RetrievalCacheConfig();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        RetrievalCacheService service = config.retrievalCacheService(300, 10, providerOf(registry));

        service.get("miss");
        service.put("hit", List.of(cachedResult()));
        service.get("hit");
        service.get("hit");

        // 指标断言：Gauge 值与缓存服务单一计数源一致
        assertThat(registry.get("retrieval_cache_hit_total").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("retrieval_cache_miss_total").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void missingMeterRegistryStillAssembles() {
        // 无 MeterRegistry 环境（指标可选）：装配不阻断
        RetrievalCacheConfig config = new RetrievalCacheConfig();

        RetrievalCacheService service = config.retrievalCacheService(300, 10, providerOf(null));

        assertThat(service).isNotNull();
        assertThat(service.hitCount()).isZero();
    }

    private VectorSearchResultVO cachedResult() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", "d1");
        return VectorSearchResultVO.builder()
                .content("R")
                .score(1.0f)
                .metadata(metadata)
                .build();
    }
}
