package cn.chyuan.ai.infrastructure.gateway.cache;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachedEmbeddingServiceTest {

    @Test
    void sameTextAndModelHitsCache() {
        IEmbeddingService delegate = mock(IEmbeddingService.class);
        when(delegate.embed("hello")).thenReturn(new float[]{1.0f, 2.0f});
        CachedEmbeddingService service = new CachedEmbeddingService(delegate, 100, 1, "model-a", null);

        assertThat(service.embed("hello")).containsExactly(1.0f, 2.0f);
        assertThat(service.embed("hello")).containsExactly(1.0f, 2.0f);

        verify(delegate, times(1)).embed("hello");
        assertThat(service.getStats().getHitCount()).isEqualTo(1);
        assertThat(service.getStats().getMissCount()).isEqualTo(1);
    }

    @Test
    void sameTextDifferentModelsUseDifferentCacheKeys() {
        IEmbeddingService delegateA = mock(IEmbeddingService.class);
        IEmbeddingService delegateB = mock(IEmbeddingService.class);
        when(delegateA.embed("hello")).thenReturn(new float[]{1.0f});
        when(delegateB.embed("hello")).thenReturn(new float[]{2.0f});

        CachedEmbeddingService modelA = new CachedEmbeddingService(delegateA, 100, 1, "model-a", null);
        CachedEmbeddingService modelB = new CachedEmbeddingService(delegateB, 100, 1, "model-b", null);

        assertThat(modelA.embed("hello")).containsExactly(1.0f);
        assertThat(modelB.embed("hello")).containsExactly(2.0f);

        verify(delegateA).embed("hello");
        verify(delegateB).embed("hello");
    }

    @Test
    void embedBatchKeepsOriginalOrder() {
        IEmbeddingService delegate = mock(IEmbeddingService.class);
        when(delegate.embedBatch(List.of("a", "b", "c"))).thenReturn(List.of(
                new float[]{1.0f},
                new float[]{2.0f},
                new float[]{3.0f}
        ));
        CachedEmbeddingService service = new CachedEmbeddingService(delegate, 100, 1, "model-a", null);

        List<float[]> results = service.embedBatch(List.of("a", "b", "c"));

        assertThat(results).hasSize(3);
        assertThat(results.get(0)).containsExactly(1.0f);
        assertThat(results.get(1)).containsExactly(2.0f);
        assertThat(results.get(2)).containsExactly(3.0f);
    }
}
