package cn.chyuan.ai.domain.segkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关键词抽取（工单 0530 BK6，jieba TF-IDF/TextRank 思想）。
 * TF-IDF：tf=词频/文档词数，idf=log(1+N/(1+df))；
 * TextRank：窗口共现无权图迭代（阻尼+收敛阈值），得分同频按词字典序确定性排序。
 */
public final class KeywordExtractor {

    /** 关键词（得分降序、同频词字典序） */
    public record Keyword(String word, double score) {
    }

    /** TF-IDF topN（corpus 提供文档频率 df，含当前文档自身） */
    public List<Keyword> tfidf(List<String> docWords, List<List<String>> corpus, int topN, Set<String> stopwords) {
        require(stopwords);
        if (docWords.isEmpty() || topN <= 0) {
            return List.of();
        }
        Map<String, Integer> tf = termFreq(docWords);
        Map<String, Integer> df = docFreq(corpus);
        List<Keyword> scored = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : tf.entrySet()) {
            String word = entry.getKey();
            if (stopwords.contains(word) || word.length() < 2) {
                continue;
            }
            double tfv = (double) entry.getValue() / docWords.size();
            double idf = Math.log(1.0 + (double) corpus.size() / (1 + df.getOrDefault(word, 0)));
            scored.add(new Keyword(word, tfv * idf));
        }
        return top(scored, topN);
    }

    /** TextRank topN：窗口共现无权无向图，(1-d)+d*Σ(rank/deg) 迭代 */
    public List<Keyword> textrank(List<String> docWords, int topN, Set<String> stopwords,
            int window, double damping, double tolerance, int maxIter) {
        require(stopwords);
        if (docWords.isEmpty() || topN <= 0) {
            return List.of();
        }
        List<String> nodes = docWords.stream().filter(w -> !stopwords.contains(w) && w.length() >= 2).distinct().toList();
        if (nodes.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            index.put(nodes.get(i), i);
        }
        List<Set<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            adj.add(new HashSet<>());
        }
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < Math.min(i + window, nodes.size()); j++) {
                adj.get(i).add(j);
                adj.get(j).add(i);
            }
        }
        double[] rank = new double[nodes.size()];
        java.util.Arrays.fill(rank, 1.0 / nodes.size());
        for (int iter = 0; iter < maxIter; iter++) {
            double[] next = new double[nodes.size()];
            for (int i = 0; i < nodes.size(); i++) {
                double sum = 0.0;
                for (int nbr : adj.get(i)) {
                    sum += rank[nbr] / adj.get(nbr).size();
                }
                next[i] = (1 - damping) + damping * sum;
            }
            double delta = 0.0;
            for (int i = 0; i < nodes.size(); i++) {
                delta += Math.abs(next[i] - rank[i]);
            }
            rank = next;
            if (delta < tolerance) {
                break;
            }
        }
        List<Keyword> scored = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            scored.add(new Keyword(nodes.get(i), rank[i]));
        }
        return top(scored, topN);
    }

    private List<Keyword> top(List<Keyword> scored, int topN) {
        scored.sort((a, b) -> {
            int byScore = Double.compare(b.score(), a.score());
            return byScore != 0 ? byScore : a.word().compareTo(b.word());
        });
        return List.copyOf(scored.subList(0, Math.min(topN, scored.size())));
    }

    private Map<String, Integer> termFreq(List<String> words) {
        Map<String, Integer> tf = new LinkedHashMap<>();
        for (String word : words) {
            tf.merge(word, 1, Integer::sum);
        }
        return tf;
    }

    private Map<String, Integer> docFreq(List<List<String>> corpus) {
        Map<String, Integer> df = new HashMap<>();
        for (List<String> doc : corpus) {
            for (String word : new HashSet<>(doc)) {
                df.merge(word, 1, Integer::sum);
            }
        }
        return df;
    }

    private void require(Set<String> stopwords) {
        if (stopwords == null) {
            throw new IllegalArgumentException("停用词表不得为 null");
        }
    }
}
