package cn.chyuan.ai.domain.tokenkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WordPiece（工单 0743 CJ4，transformers 思想）。
 * ## 续词前缀/贪心最长匹配/UNK 兜底/训练语料建词表。
 */
public final class WordPiece {

    public static final String UNK = "[UNK]";
    public static final String CONTINUATION = "##";

    private final Map<String, Integer> vocab;

    public WordPiece(Map<String, Integer> vocab) {
        if (vocab == null || !vocab.containsKey(UNK)) {
            throw new IllegalArgumentException("词表须含 [UNK]");
        }
        this.vocab = new LinkedHashMap<>(vocab);
    }

    /** 从语料建词表：全词 + 字符（含 ## 续词形态），频率阈值过滤 */
    public static Map<String, Integer> buildVocab(Map<String, Integer> wordCounts, int minFreq, int maxSize) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (Map.Entry<String, Integer> e : wordCounts.entrySet()) {
            if (e.getValue() >= minFreq) {
                counts.put(e.getKey(), e.getValue());
                for (int i = 1; i < e.getKey().length(); i++) {
                    counts.merge("##" + e.getKey().substring(i), e.getValue(), Integer::sum);
                }
            }
        }
        for (String word : wordCounts.keySet()) {
            for (char c : word.toCharArray()) {
                counts.merge(String.valueOf(c), 0, Integer::sum);
            }
        }
        Map<String, Integer> vocab = new LinkedHashMap<>();
        vocab.put(UNK, vocab.size());
        vocab.put("[PAD]", vocab.size());
        for (String token : counts.keySet()) {
            if (vocab.size() >= maxSize) {
                break;
            }
            vocab.putIfAbsent(token, vocab.size());
        }
        return vocab;
    }

    /** 贪心最长匹配：start 全词，续段 ## 前缀；无法覆盖整词 → UNK */
    public List<String> encodeWord(String word) {
        if (word == null || word.isEmpty()) {
            throw new IllegalArgumentException("空词");
        }
        List<String> tokens = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            String match = null;
            while (end > start) {
                String candidate = start == 0 ? word.substring(start, end) : CONTINUATION + word.substring(start, end);
                if (vocab.containsKey(candidate)) {
                    match = candidate;
                    break;
                }
                end--;
            }
            if (match == null) {
                return List.of(UNK);
            }
            tokens.add(match);
            start = end;
        }
        return tokens;
    }

    public Integer id(String token) {
        return vocab.get(token);
    }

    public Map<String, Integer> vocab() {
        return Map.copyOf(vocab);
    }

    public int size() {
        return vocab.size();
    }
}
