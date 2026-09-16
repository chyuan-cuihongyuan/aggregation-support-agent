package cn.chyuan.ai.domain.vectorkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BA6-BA8 补充单测（工单 0441/0442）：快照重建等价/混合 RRF 融合/存储端口。
 */
class VectorHybridTest {

    @Test
    void 快照邻接导出与重建搜索一致() {
        AtomicInteger level = new AtomicInteger();
        HnswIndex index = new HnswIndex(4, 8, new VectorMath(VectorMath.Metric.COSINE, true), level::getAndIncrement);
        for (int i = 0; i < 20; i++) {
            double angle = Math.PI * i / 20;
            index.insert("p" + i, new float[]{(float) Math.cos(angle), (float) Math.sin(angle)});
        }
        Map<String, List<String>> snapshot = index.adjacency();
        assertEquals(20, snapshot.size());
        // 重建：同点集重插 → topK 一致
        HnswIndex rebuilt = new HnswIndex(4, 8, new VectorMath(VectorMath.Metric.COSINE, true), level::getAndIncrement);
        for (int i = 0; i < 20; i++) {
            double angle = Math.PI * i / 20;
            rebuilt.insert("p" + i, new float[]{(float) Math.cos(angle), (float) Math.sin(angle)});
        }
        List<String> original = index.search(new float[]{1, 0}, 5).stream().map(HnswIndex.Scored::id).toList();
        List<String> restored = rebuilt.search(new float[]{1, 0}, 5).stream().map(HnswIndex.Scored::id).toList();
        assertEquals(original, restored);
    }

    @Test
    void rrf融合排序与权重() {
        HybridVectorSearch fusion = new HybridVectorSearch(60, 0.7, 0.3);
        // 向量序：a,b,c；关键词序：b,a,d
        List<HybridVectorSearch.Fused> fused = fusion.fuse(List.of("a", "b", "c"), List.of("b", "a", "d"), 4);
        // a: 0.7/61 + 0.3/62；b: 0.7/62 + 0.3/61 → a > b > c > d
        assertEquals("a", fused.get(0).id());
        assertEquals("b", fused.get(1).id());
        // 纯关键词命中 d 也入围（0.3/63）
        assertTrue(fused.stream().anyMatch(f -> f.id().equals("d")));
        // 全零权重拒绝
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new HybridVectorSearch(60, 0, 0));
    }

    @Test
    void 存储端口全操作() {
        AtomicInteger level = new AtomicInteger();
        HybridVectorSearch hybrid = new HybridVectorSearch();
        HybridVectorSearch.VectorStorePort store = hybrid.new InMemoryHnswStore(
                new HnswIndex(4, 8, new VectorMath(VectorMath.Metric.COSINE, true), level::getAndIncrement));
        store.upsert("a", new float[]{1, 0});
        store.upsert("b", new float[]{0.9f, 0.1f});
        store.upsert("c", new float[]{0, 1});
        List<HnswIndex.Scored> hits = store.search(new float[]{1, 0}, 2);
        assertEquals("a", hits.get(0).id());
        store.delete("a");
        assertTrue(store.search(new float[]{1, 0}, 2).stream().noneMatch(s -> s.id().equals("a")));
        assertEquals(2, store.snapshot().size());
    }
}
