package cn.chyuan.ai.domain.tokenkernel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * BPE 训练器与模型（工单 0742 CJ3，transformers 思想）。
 * 词频统计/pair 计数/最优 pair 迭代合并至词表上限/合并秩导出/平票字典序确定性。
 */
public final class BpeTrainer {

    /** BPE 模型：词表（token→id）与合并规则（按秩排序） */
    public record Model(Map<String, Integer> vocab, List<String[]> merges) {

        /** 词内编码：初始字符序列，按秩贪心合并（未知字符回退 <0xHEX> 字节形态此处用 UNK 兜底由上层处理） */
        public List<String> encodeWord(String word) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < word.length(); i++) {
                parts.add(String.valueOf(word.charAt(i)));
            }
            while (parts.size() > 1) {
                int bestRank = Integer.MAX_VALUE;
                int bestAt = -1;
                for (int i = 0; i + 1 < parts.size(); i++) {
                    String pair = parts.get(i) + " " + parts.get(i + 1);
                    int at = merges.indexOf(new String[]{parts.get(i), parts.get(i + 1)});
                    if (at >= 0 && at < bestRank) {
                        bestRank = at;
                        bestAt = i;
                    }
                }
                if (bestAt < 0) {
                    break;
                }
                parts.set(bestAt, parts.get(bestAt) + parts.get(bestAt + 1));
                parts.remove(bestAt + 1);
            }
            return parts;
        }

        public int id(String token) {
            return vocab.getOrDefault(token, -1);
        }
    }

    private BpeTrainer() {
    }

    /** 训练：词频语料 → 迭代合并最优 pair（频次最高，平票取字典序小）至词表上限 */
    public static Model train(Map<String, Integer> wordCounts, int vocabSize) {
        if (wordCounts == null || wordCounts.isEmpty()) {
            throw new IllegalArgumentException("空语料");
        }
        if (vocabSize < 16) {
            throw new IllegalArgumentException("词表上限过小");
        }
        // 词 → 字符序列（词频排序保证确定性迭代）
        Map<String, List<String>> splits = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : wordCounts.entrySet()) {
            List<String> chars = new ArrayList<>();
            for (char c : e.getKey().toCharArray()) {
                chars.add(String.valueOf(c));
            }
            splits.put(e.getKey(), chars);
        }
        List<String[]> merges = new ArrayList<>();
        int initialChars = initialAlphabet(wordCounts).size();
        while (vocabTokenCount(wordCounts, initialChars, merges) < vocabSize) {
            Map<String, Integer> pairFreq = new TreeMap<>();
            for (Map.Entry<String, Integer> e : wordCounts.entrySet()) {
                List<String> parts = splits.get(e.getKey());
                for (int i = 0; i + 1 < parts.size(); i++) {
                    String pair = parts.get(i) + "\u0000" + parts.get(i + 1);
                    pairFreq.merge(pair, e.getValue(), Integer::sum);
                }
            }
            if (pairFreq.isEmpty()) {
                break;
            }
            String best = null;
            int bestFreq = 0;
            for (Map.Entry<String, Integer> e : pairFreq.entrySet()) {
                if (e.getValue() > bestFreq || (e.getValue() == bestFreq && best != null && e.getKey().compareTo(best) < 0)) {
                    best = e.getKey();
                    bestFreq = e.getValue();
                }
            }
            if (best == null || bestFreq <= 0) {
                break;
            }
            String[] parts = best.split("\u0000", -1);
            merges.add(parts);
            for (List<String> seq : splits.values()) {
                for (int i = 0; i + 1 < seq.size(); ) {
                    if (seq.get(i).equals(parts[0]) && seq.get(i + 1).equals(parts[1])) {
                        seq.set(i, parts[0] + parts[1]);
                        seq.remove(i + 1);
                    } else {
                        i++;
                    }
                }
            }
        }
        Map<String, Integer> vocab = new LinkedHashMap<>();
        int id = 0;
        vocab.put("<unk>", id++);
        vocab.put("<pad>", id++);
        for (String c : initialAlphabet(wordCounts)) {
            if (!vocab.containsKey(c)) {
                vocab.put(c, id++);
            }
        }
        for (String[] merge : merges) {
            String token = merge[0] + merge[1];
            if (!vocab.containsKey(token)) {
                vocab.put(token, id++);
            }
        }
        return new Model(vocab, List.copyOf(merges));
    }

    private static int vocabTokenCount(Map<String, Integer> wordCounts, int alphabet, List<String[]> merges) {
        java.util.Set<String> tokens = new java.util.TreeSet<>();
        for (String word : wordCounts.keySet()) {
            for (char c : word.toCharArray()) {
                tokens.add(String.valueOf(c));
            }
        }
        for (String[] merge : merges) {
            tokens.add(merge[0] + merge[1]);
        }
        return tokens.size() + 2;
    }

    /** 初始字母表（排序确定） */
    public static List<String> initialAlphabet(Map<String, Integer> wordCounts) {
        java.util.Set<String> alphabet = new java.util.TreeSet<>();
        for (String word : wordCounts.keySet()) {
            for (char c : word.toCharArray()) {
                alphabet.add(String.valueOf(c));
            }
        }
        return new ArrayList<>(alphabet);
    }

    /** 词频合法性 */
    public static Map<String, Integer> checkCorpus(Map<String, Integer> wordCounts) {
        for (Map.Entry<String, Integer> e : wordCounts.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) {
                throw new IllegalArgumentException("词频须为正: " + e.getKey());
            }
            if (e.getKey().isEmpty()) {
                throw new IllegalArgumentException("空词");
            }
        }
        return wordCounts;
    }

    static Comparator<String[]> mergeOrder() {
        return Comparator.comparing(a -> a[0] + " " + a[1]);
    }
}
