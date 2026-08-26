package cn.chyuan.ai.infrastructure.gateway.cache;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void cacheKeyUsesSha256Digest() {
        CachedEmbeddingService service = new CachedEmbeddingService(mock(IEmbeddingService.class), 100, 1, "model-a", null);

        String key = service.generateCacheKey("hello", "SHA-256");

        // 键前缀与结构保持：embedding:<model>:<hex>
        assertThat(key).startsWith("embedding:model-a:");
        String hex = key.substring("embedding:model-a:".length());
        assertThat(hex).hasSize(64); // SHA-256 摘要为 256bit = 64 个 hex 字符
        assertThat(hex).matches("[0-9a-f]+");
    }

    @Test
    void cacheKeyHashCodeFallbackWhenAlgorithmUnavailable() {
        CachedEmbeddingService service = new CachedEmbeddingService(mock(IEmbeddingService.class), 100, 1, "model-a", null);

        // 传入不存在的算法名触发 NoSuchAlgorithmException，走降级路径：回退文本 hashCode
        String key = service.generateCacheKey("hello", "not-a-real-algo");

        assertThat(key).isEqualTo("embedding:model-a:" + "hello".hashCode());
    }

    @Test
    void embedBatchHitsCacheForAlreadyCachedTexts() {
        IEmbeddingService delegate = mock(IEmbeddingService.class);
        when(delegate.embed("a")).thenReturn(new float[]{1.0f});
        CachedEmbeddingService service = new CachedEmbeddingService(delegate, 100, 1, "model-a", null);

        // 预热缓存（首请求走委托）
        service.embed("a");

        // 批量请求命中缓存，不再委托调用
        List<float[]> results = service.embedBatch(List.of("a"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).containsExactly(1.0f);
        verify(delegate, never()).embedBatch(anyList());
        verify(delegate, times(1)).embed("a");
        assertThat(service.getStats().getHitCount()).isEqualTo(1);
        assertThat(service.getStats().getMissCount()).isEqualTo(1);
    }

    @Test
    void embedBatchOnlyDelegatesUncachedTexts() {
        IEmbeddingService delegate = mock(IEmbeddingService.class);
        when(delegate.embed("a")).thenReturn(new float[]{1.0f});
        when(delegate.embedBatch(List.of("b"))).thenReturn(List.of(new float[]{2.0f}));
        CachedEmbeddingService service = new CachedEmbeddingService(delegate, 100, 1, "model-a", null);

        // 预热 a，随后批量 [a, b]：a 命中缓存，仅 b 委托嵌入
        service.embed("a");
        List<float[]> results = service.embedBatch(List.of("a", "b"));

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).containsExactly(1.0f);
        assertThat(results.get(1)).containsExactly(2.0f);
        verify(delegate, times(1)).embedBatch(List.of("b"));
        assertThat(service.getStats().getHitCount()).isEqualTo(1);
        assertThat(service.getStats().getMissCount()).isEqualTo(2);
    }
}
