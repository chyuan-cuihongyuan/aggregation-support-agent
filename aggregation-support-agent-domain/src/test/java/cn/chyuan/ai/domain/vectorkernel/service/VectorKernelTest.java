package cn.chyuan.ai.domain.vectorkernel.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BA1-BA5 单测（工单 0435-0439）：距离内核/HNSW 建图搜索/标量量化/过滤检索。
 */
class VectorKernelTest {

    @Test
    void 三度量手算对照与归一口径() {
        VectorMath cosine = new VectorMath(VectorMath.Metric.COSINE, false);
        float[] a = {1, 0};
        float[] b = {0, 1};
        float[] c = {1, 1};
        assertEquals(0.0, cosine.similarity(a, b), 1e-9);
        assertEquals(1.0, cosine.similarity(a, a), 1e-9);
        assertEquals(Math.sqrt(0.5), cosine.similarity(a, c), 1e-6);
        // 点积
        VectorMath dot = new VectorMath(VectorMath.Metric.DOT, false);
        assertEquals(3.0, dot.similarity(new float[]{1, 1}, new float[]{1, 2}), 1e-9);
        // 欧氏（相似度口径=负距离）
        VectorMath euclid = new VectorMath(VectorMath.Metric.EUCLIDEAN, false);
        assertEquals(-5.0, euclid.similarity(new float[]{0, 0}, new float[]{3, 4}), 1e-9);
        // 归一化后余弦=点积（float 精度 1e-6）
        VectorMath cosineNorm = new VectorMath(VectorMath.Metric.COSINE, true);
        VectorMath dotNorm = new VectorMath(VectorMath.Metric.DOT, true);
        assertEquals(cosineNorm.similarity(c, a), dotNorm.similarity(c, a), 1e-6);
        // 维度不一致拒绝
        assertThrows(IllegalArgumentException.class, () -> cosine.similarity(a, new float[]{1}));
    }

    @Test
    void hnsw建图搜索召回与剪枝() {
        AtomicInteger level = new AtomicInteger();
        // 确定性层级：交替 0/1
        HnswIndex index = new HnswIndex(4, 8, new VectorMath(VectorMath.Metric.COSINE, true),
                () -> level.getAndIncrement() % 2);
        // 100 个二维单位圆点：偶数 i 与查询 (1,0) 近
        for (int i = 0; i < 100; i++) {
            double angle = Math.PI * 2 * i / 100;
            index.insert("p" + i, new float[]{(float) Math.cos(angle), (float) Math.sin(angle)});
        }
        List<HnswIndex.Scored> top1 = index.search(new float[]{1, 0}, 1);
        assertEquals("p0", top1.get(0).id());
        // top10 与暴力对照召回率 ≥ 0.8
        List<String> hnsw = index.search(new float[]{1, 0}, 10).stream().map(HnswIndex.Scored::id).toList();
        List<float[]> all = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            double angle = Math.PI * 2 * i / 100;
            all.add(new float[]{(float) Math.cos(angle), (float) Math.sin(angle)});
            ids.add("p" + i);
        }
        List<String> brute = bruteTopK(all, ids, new float[]{1, 0}, 10);
        long hit = hnsw.stream().filter(brute::contains).count();
        assertTrue(hit >= 8, "召回率应 ≥ 0.8，实际 " + hit + "/10");
        // 邻接数不超 maxM=4
        for (List<String> neighbors : index.adjacency().values()) {
            assertTrue(neighbors.size() <= 4);
        }
    }

    @Test
    void upsert覆盖幂等与墓碑恢复() {
        AtomicInteger level = new AtomicInteger();
        HnswIndex index = new HnswIndex(4, 8, new VectorMath(VectorMath.Metric.COSINE, true), level::getAndIncrement);
        assertTrue(index.insert("x", new float[]{1, 0}));
        assertFalse(index.insert("x", new float[]{0, 1}));
        index.insert("y", new float[]{0, 1});
        assertEquals(2, index.size());
        // 删除墓碑：搜索不可达
        index.delete("y");
        assertEquals(1, index.size());
        List<HnswIndex.Scored> hits = index.search(new float[]{0, 1}, 5);
        assertTrue(hits.stream().noneMatch(s -> s.id().equals("y")));
        // 恢复：重新可达
        index.restore("y");
        assertEquals(2, index.size());
        assertTrue(index.search(new float[]{0, 1}, 5).stream().anyMatch(s -> s.id().equals("y")));
    }

    @Test
    void 标量量化往返与误差上界() {
        ScalarQuantizer quantizer = new ScalarQuantizer();
        float[] vector = {0.1f, -0.9f, 0.55f, 0.32f};
        ScalarQuantizer.Quantized quantized = quantizer.quantize(vector);
        assertEquals(4, quantized.codes().length);
        float[] restored = quantizer.dequantize(quantized);
        for (int i = 0; i < vector.length; i++) {
            assertEquals(vector[i], restored[i], 0.01f);
        }
        // 误差上界
        assertTrue(quantizer.withinErrorBound(vector, new float[]{0.2f, -0.8f, 0.5f, 0.3f}, 1.0));
        // 常数向量保护：全 0 码且反量化回常数
        ScalarQuantizer.Quantized constant = quantizer.quantize(new float[]{2, 2, 2});
        assertArrayEqualsAllZero(constant.codes());
        assertEquals(2.0f, quantizer.dequantize(constant)[0], 1e-9);
        // 码点积维度拒绝
        assertThrows(IllegalArgumentException.class, () -> ScalarQuantizer.dotCodes(new byte[1], new byte[2]));
    }

    @Test
    void 元数据过滤前置后置与策略选择() {
        VectorMath math = new VectorMath(VectorMath.Metric.COSINE, true);
        FilteredVectorSearch search = new FilteredVectorSearch(math);
        List<FilteredVectorSearch.Tagged> points = List.of(
                tagged("p1", new float[]{1, 0}, "tier", "gold"),
                tagged("p2", new float[]{0.9f, 0.1f}, "tier", "silver"),
                tagged("p3", new float[]{0.8f, 0.2f}, "tier", "gold"),
                tagged("p4", new float[]{0.1f, 0.9f}, "tier", "bronze"));
        FilteredVectorSearch.TagPredicate gold = FilteredVectorSearch.equalsTag("tier", "gold");
        // 前置过滤：结果全 gold
        List<FilteredVectorSearch.Scored> pre = search.preFilter(points, new float[]{1, 0}, 2, gold);
        assertEquals(2, pre.size());
        assertTrue(pre.stream().allMatch(s -> s.id().equals("p1") || s.id().equals("p3")));
        // 后置过滤：pool 大池再筛
        List<FilteredVectorSearch.Scored> post = search.postFilter(points, new float[]{1, 0}, 1, gold, 3);
        assertEquals(1, post.size());
        assertEquals("p1", post.get(0).id());
        // 范围与合取谓词
        FilteredVectorSearch.TagPredicate mixed = FilteredVectorSearch.allOf(gold,
                FilteredVectorSearch.rangeTag("score", 10, 100));
        List<FilteredVectorSearch.Tagged> withScore = List.of(
                tagged("p1", new float[]{1, 0}, "tier", "gold", "score", 50),
                tagged("p3", new float[]{0.8f, 0.2f}, "tier", "gold", "score", 5));
        assertEquals(1, search.preFilter(withScore, new float[]{1, 0}, 5, mixed).size());
        // 选择性强（gold 仅 2/4=0.5 > 0.3 → 后置）自动策略仍正确
        assertEquals("p1", search.search(points, new float[]{1, 0}, 1, gold).get(0).id());
    }

    private static FilteredVectorSearch.Tagged tagged(String id, float[] vector, Object... kv) {
        Map<String, Object> tags = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            tags.put((String) kv[i], kv[i + 1]);
        }
        return new FilteredVectorSearch.Tagged(id, vector, tags);
    }

    private static List<String> bruteTopK(List<float[]> vectors, List<String> ids, float[] query, int k) {
        List<HnswIndex.Scored> scored = new ArrayList<>();
        VectorMath math = new VectorMath(VectorMath.Metric.COSINE, true);
        for (int i = 0; i < vectors.size(); i++) {
            scored.add(new HnswIndex.Scored(ids.get(i), math.similarity(query, vectors.get(i))));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.subList(0, k).stream().map(HnswIndex.Scored::id).toList();
    }

    private static void assertArrayEqualsAllZero(byte[] codes) {
        for (byte code : codes) {
            assertEquals(0, code);
        }
    }
}
