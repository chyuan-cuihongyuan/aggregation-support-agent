package cn.chyuan.ai.domain.segkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户词典与屏蔽词（工单 0528 BK4，jieba 用户词典思想）。
 * 用户词强制成词（优先于统计路径，带自定义词频参与概率）/
 * 屏蔽词强制切开（命中处只允许单字边）/装载后重算 DAG 生效。
 */
public final class UserDictOverlay {

    private final Map<String, Long> userWords = new HashMap<>();
    private final Map<String, Boolean> blocked = new HashMap<>();

    /** 添加用户词（空词与非正词频拒绝） */
    public void addWord(String word, long freq) {
        if (word == null || word.isEmpty()) {
            throw new IllegalArgumentException("用户词不得为空");
        }
        if (freq <= 0) {
            throw new IllegalArgumentException("用户词频须为正: " + word);
        }
        userWords.put(word, freq);
        blocked.remove(word);
    }

    /** 添加屏蔽词（强制切开） */
    public void blockWord(String word) {
        if (word == null || word.length() < 2) {
            throw new IllegalArgumentException("屏蔽词须长度≥2（单字无从切开）");
        }
        blocked.put(word, Boolean.TRUE);
        userWords.remove(word);
    }

    public boolean isUser(String word) {
        return userWords.containsKey(word);
    }

    public boolean isBlocked(String word) {
        return blocked.containsKey(word);
    }

    public Map<String, Long> userWords() {
        return Map.copyOf(userWords);
    }

    /** 屏蔽词在句子中的命中区间（start 含 end 排他，升序） */
    public java.util.List<int[]> blockedRanges(String sentence) {
        java.util.List<int[]> ranges = new ArrayList<>();
        for (String word : blocked.keySet()) {
            int from = 0;
            int hit;
            while ((hit = sentence.indexOf(word, from)) >= 0) {
                ranges.add(new int[]{hit, hit + word.length()});
                from = hit + 1;
            }
        }
        ranges.sort(java.util.Comparator.comparingInt((int[] r) -> r[0]).thenComparingInt(r -> r[1]));
        return ranges;
    }

    /**
     * 在基础 DAG 上施加覆盖：用户词补边（缺失则加）；屏蔽词命中区间
     * 收紧为仅单字边（整词边与跨界边全部移除）。返回新 DAG 不改入参。
     */
    public List<List<Integer>> apply(List<List<Integer>> baseDag, String sentence) {
        int n = sentence.length();
        List<List<Integer>> dag = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            dag.add(new java.util.ArrayList<>(baseDag.get(i)));
        }
        for (String word : userWords.keySet()) {
            addAllOccurrences(dag, sentence, word);
        }
        for (String word : blocked.keySet()) {
            tightenToSingles(dag, sentence, word);
        }
        for (int i = 0; i < n; i++) {
            dag.get(i).sort(Integer::compareTo);
        }
        return List.copyOf(dag);
    }

    private void addAllOccurrences(List<List<Integer>> dag, String sentence, String word) {
        int from = 0;
        int hit;
        while ((hit = sentence.indexOf(word, from)) >= 0) {
            List<Integer> ends = dag.get(hit);
            int end = hit + word.length();
            if (!ends.contains(end)) {
                ends.add(end);
            }
            from = hit + 1;
        }
    }

    private void tightenToSingles(List<List<Integer>> dag, String sentence, String word) {
        int from = 0;
        int hit;
        while ((hit = sentence.indexOf(word, from)) >= 0) {
            int end = hit + word.length();
            List<Integer> ends = dag.get(hit);
            ends.clear();
            ends.add(hit + 1);
            for (int i = hit + 1; i < end; i++) {
                final int at = i;
                List<Integer> inner = dag.get(i);
                inner.removeIf(e -> e > at + 1 || e > end);
                if (!inner.contains(at + 1)) {
                    inner.add(at + 1);
                }
            }
            from = hit + 1;
        }
    }
}
