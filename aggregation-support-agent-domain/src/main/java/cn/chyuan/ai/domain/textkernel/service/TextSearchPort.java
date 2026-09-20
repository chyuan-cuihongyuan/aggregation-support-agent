package cn.chyuan.ai.domain.textkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索端口+混合加权排序（工单 0495 BG8）。
 * TextSearchPort（索引/检索/建议/切面四面）/最终分 = BM25 × 字段权重 + recency 加权
 * （新鲜度半衰指数衰减）/text-kernel.enabled 默认关（0462-D5 不改 searchkernel）。
 */
public interface TextSearchPort {

    /** 检索结果项：文档 + 最终分 + BM25 明细 */
    record Hit(int docId, double finalScore, double bm25Score, double recencyBonus) {
    }

    /** 检索：词频向量 + 文档时间戳（epoch ms）参与 recency 加权 */
    List<Hit> search(Map<String, Integer> queryTermFreqs);

    /** 内存假实现：组合 BM25 + 字段权重 + recency 半衰 */
    class InMemoryTextSearch implements TextSearchPort {

        private final Bm25Scorer bm25;
        private final Map<Integer, Long> docTimestamps = new HashMap<>();
        private final Map<Integer, Map<String, Integer>> docTermFreqs = new HashMap<>();
        private final double fieldWeight;
        private final double halfLifeMillis;
        private final long nowMillis;

        public InMemoryTextSearch(double fieldWeight, double halfLifeMillis, long nowMillis) {
            if (halfLifeMillis <= 0) {
                throw new IllegalArgumentException("半衰须 > 0");
            }
            this.bm25 = new Bm25Scorer(1.2, 0.75);
            this.fieldWeight = fieldWeight;
            this.halfLifeMillis = halfLifeMillis;
            this.nowMillis = nowMillis;
        }

        /** 索引文档（词元 + 时间戳） */
        public synchronized void index(int docId, List<AnalyzerChain.Token> tokens, long timestampMillis) {
            List<String> terms = tokens.stream().map(AnalyzerChain.Token::text).toList();
            bm25.indexDoc(docId, terms);
            Map<String, Integer> tf = new HashMap<>();
            for (String term : terms) {
                tf.merge(term, 1, Integer::sum);
            }
            docTermFreqs.put(docId, tf);
            docTimestamps.put(docId, timestampMillis);
        }

        @Override
        public synchronized List<Hit> search(Map<String, Integer> queryTermFreqs) {
            List<Hit> hits = new ArrayList<>();
            for (Integer docId : docTimestamps.keySet()) {
                Map<String, Integer> tf = docTermFreqs.get(docId);
                Map<String, Integer> docTfForQuery = new HashMap<>();
                queryTermFreqs.keySet().forEach(term -> docTfForQuery.put(term, tf.getOrDefault(term, 0)));
                double bm25Score = bm25.score(docId, docTfForQuery) * fieldWeight;
                if (bm25Score <= 0) {
                    continue;
                }
                double age = Math.max(0, nowMillis - docTimestamps.get(docId));
                double recencyBonus = bm25Score * Math.pow(0.5, age / halfLifeMillis);
                hits.add(new Hit(docId, bm25Score + recencyBonus, bm25Score, recencyBonus));
            }
            hits.sort(java.util.Comparator.comparingDouble(Hit::finalScore).reversed()
                    .thenComparingInt(Hit::docId));
            return List.copyOf(hits);
        }
    }
}
