package cn.chyuan.ai.domain.segkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 前缀词典与切分 DAG（工单 0525 BK1，jieba 前缀词典思想）。
 * 词条 Trie（词→词频）/句子切分有向无环图（全部成词候选路径，含单字兜底）/
 * 空词条与非正词频拒绝。纯函数，无状态副作用除词典装载。
 */
public final class TrieDict {

    /** 兜底平滑最小词频（未登录单字按此参与概率） */
    public static final long MIN_FREQ = 1L;

    private static final class Node {
        final Map<Character, Node> children = new HashMap<>();
        long freq;
        boolean word;
    }

    private final Node root = new Node();
    private long totalFreq;

    /** 装载词条（空词与非正词频拒绝） */
    public void add(String word, long freq) {
        if (word == null || word.isEmpty()) {
            throw new IllegalArgumentException("词条不得为空");
        }
        if (freq <= 0) {
            throw new IllegalArgumentException("词频须为正: " + word);
        }
        Node cur = root;
        for (int i = 0; i < word.length(); i++) {
            cur = cur.children.computeIfAbsent(word.charAt(i), c -> new Node());
        }
        if (!cur.word) {
            totalFreq += freq;
        }
        cur.word = true;
        cur.freq = freq;
    }

    public boolean contains(String word) {
        Node node = walk(word);
        return node != null && node.word;
    }

    /** 词频（未登录返回 0） */
    public long freq(String word) {
        Node node = walk(word);
        return node != null && node.word ? node.freq : 0L;
    }

    /** 词典总词频（概率归一分母） */
    public long totalFreq() {
        return totalFreq;
    }

    /**
     * 句子切分 DAG：dag.get(i) = 以 i 开始可成词的终边集合（index 排他，升序，
     * 恒含 i+1 单字兜底）。空串返回空图。
     */
    public List<List<Integer>> dag(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("句子不得为 null");
        }
        int n = sentence.length();
        List<List<Integer>> dag = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<Integer> ends = new ArrayList<>();
            ends.add(i + 1);
            Node cur = root;
            for (int j = i; j < n; j++) {
                cur = cur.children.get(sentence.charAt(j));
                if (cur == null) {
                    break;
                }
                if (cur.word && j + 1 > i + 1) {
                    ends.add(j + 1);
                }
            }
            ends.sort(Integer::compareTo);
            dag.add(ends);
        }
        return dag;
    }

    private Node walk(String word) {
        if (word == null || word.isEmpty()) {
            return null;
        }
        Node cur = root;
        for (int i = 0; i < word.length() && cur != null; i++) {
            cur = cur.children.get(word.charAt(i));
        }
        return cur;
    }
}
