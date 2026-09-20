package cn.chyuan.ai.domain.textkernel.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 评分（工单 0489 BG2，elasticsearch BM25 思想）。
 * k₁/b 参数/逆文档频率（ln(1+(N-n+0.5)/(n+0.5))）/字段长度归一
 * /可解释评分明细（tf 贡献与 idf 分离输出）。
 */
public class Bm25Scorer {

    /** 评分明细 */
    public record ScoreBreakdown(double idf, double tfNorm, double score, int termFreq, int fieldLength) {
    }

    private final double k1;
    private final double b;
    private final Map<String, Integer> docFreq = new HashMap<>();
    private final Map<Integer, Integer> fieldLengths = new HashMap<>();
    private long totalDocs;
    private double avgFieldLength;

    public Bm25Scorer(double k1, double b) {
        if (k1 <= 0 || b < 0 || b > 1) {
            throw new IllegalArgumentException("参数非法: 须 k1>0 且 0≤b≤1");
        }
        this.k1 = k1;
        this.b = b;
    }

    /** 登记文档（字段词元列表），维护 df 与字段长度 */
    public synchronized void indexDoc(int docId, List<String> terms) {
        terms.stream().distinct().forEach(term -> docFreq.merge(term, 1, Integer::sum));
        fieldLengths.put(docId, terms.size());
        totalDocs++;
        avgFieldLength = (avgFieldLength * (totalDocs - 1) + terms.size()) / totalDocs;
    }

    /** 单词元评分明细（文档未含该词时 tf=0 → score=0） */
    public synchronized ScoreBreakdown score(int docId, String term, int termFreq) {
        int n = docFreq.getOrDefault(term, 0);
        double idf = Math.log(1 + (totalDocs - n + 0.5) / (n + 0.5));
        int fieldLength = fieldLengths.getOrDefault(docId, 0);
        double tfNorm = termFreq * (k1 + 1)
                / (termFreq + k1 * (1 - b + b * fieldLength / Math.max(1e-9, avgFieldLength)));
        return new ScoreBreakdown(idf, tfNorm, idf * tfNorm, termFreq, fieldLength);
    }

    /** 多词元求和评分 */
    public synchronized double score(int docId, Map<String, Integer> termFreqs) {
        return termFreqs.entrySet().stream()
                .mapToDouble(entry -> score(docId, entry.getKey(), entry.getValue()).score())
                .sum();
    }

    public synchronized long totalDocs() {
        return totalDocs;
    }

    public synchronized int docFreq(String term) {
        return docFreq.getOrDefault(term, 0);
    }
}
