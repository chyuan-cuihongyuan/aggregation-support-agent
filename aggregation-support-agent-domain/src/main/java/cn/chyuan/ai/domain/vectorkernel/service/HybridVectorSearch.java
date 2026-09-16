package cn.chyuan.ai.domain.vectorkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索 + 向量存储端口（工单 0442 BA8）。
 * 向量 topK × 关键词命中集合 RRF 融合（复用 AS6 口径 k=60 可配，权重可配）
 * + VectorStorePort 内存 HNSW 实现；vector-kernel.enabled 默认关不接检索主链路。
 */
public class HybridVectorSearch {

    /** RRF 常数默认（沿 AS6 口径） */
    public static final int DEFAULT_RRF_K = 60;

    /** 融合结果 */
    public record Fused(String id, double score) {
    }

    /** 向量存储端口 */
    public interface VectorStorePort {

        void upsert(String id, float[] vector);

        void delete(String id);

        List<HnswIndex.Scored> search(float[] query, int k);

        Map<String, List<String>> snapshot();
    }

    /** 内存 HNSW 实现 */
    class InMemoryHnswStore implements VectorStorePort {

        private final HnswIndex index;

        InMemoryHnswStore(HnswIndex index) {
            this.index = index;
        }

        @Override
        public void upsert(String id, float[] vector) {
            index.insert(id, vector);
        }

        @Override
        public void delete(String id) {
            index.delete(id);
        }

        @Override
        public List<HnswIndex.Scored> search(float[] query, int k) {
            return index.search(query, k);
        }

        @Override
        public Map<String, List<String>> snapshot() {
            return index.adjacency();
        }
    }

    private final int rrfK;
    private final double vectorWeight;
    private final double keywordWeight;

    public HybridVectorSearch(int rrfK, double vectorWeight, double keywordWeight) {
        if (rrfK < 1 || vectorWeight < 0 || keywordWeight < 0 || vectorWeight + keywordWeight <= 0) {
            throw new IllegalArgumentException("RRF k 至少 1 且权重不可全负");
        }
        this.rrfK = rrfK;
        this.vectorWeight = vectorWeight;
        this.keywordWeight = keywordWeight;
    }

    public HybridVectorSearch() {
        this(DEFAULT_RRF_K, 0.7, 0.3);
    }

    /**
     * RRF 融合：score = Σ weight/(k+rank)（rank 从 1 起）；同 id 相加，按分降序并列按 id。
     */
    public List<Fused> fuse(List<String> vectorTopIds, List<String> keywordHitIds, int k) {
        Map<String, Double> scores = new LinkedHashMap<>();
        accumulate(scores, vectorTopIds, vectorWeight);
        accumulate(scores, keywordHitIds, keywordWeight);
        List<Fused> out = new ArrayList<>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            out.add(new Fused(entry.getKey(), entry.getValue()));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()) != 0
                ? Double.compare(b.score(), a.score())
                : a.id().compareTo(b.id()));
        return out.subList(0, Math.min(k, out.size()));
    }

    private void accumulate(Map<String, Double> scores, List<String> ids, double weight) {
        for (int rank = 1; rank <= ids.size(); rank++) {
            scores.merge(ids.get(rank - 1), weight / (rrfK + rank), Double::sum);
        }
    }
}
