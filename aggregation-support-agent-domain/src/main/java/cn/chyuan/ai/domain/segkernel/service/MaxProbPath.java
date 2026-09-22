package cn.chyuan.ai.domain.segkernel.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 最大概率路径（工单 0526 BK2，jieba 动态规划思想）。
 * 词概率 = 词频/总频，代价 = -log(p)；未登录词按 TrieDict.MIN_FREQ 平滑。
 * 自后向前 DP 求全局最优切分路径，输出确定性。
 */
public final class MaxProbPath {

    /** 全局最优切分（空串返回空表；null 拒绝） */
    public List<String> cut(TrieDict dict, String sentence) {
        return cut(dict, null, sentence);
    }

    /** 全局最优切分（overlay 感知：用户词补边且词频参与概率） */
    public List<String> cut(TrieDict dict, UserDictOverlay overlay, String sentence) {
        if (dict == null) {
            throw new IllegalArgumentException("词典不得为 null");
        }
        if (sentence == null) {
            throw new IllegalArgumentException("句子不得为 null");
        }
        int n = sentence.length();
        if (n == 0) {
            return List.of();
        }
        List<List<Integer>> dag = overlay == null ? dict.dag(sentence)
                : overlay.apply(dict.dag(sentence), sentence);
        // best[i]：从 i 到 n 的最小代价；next[i]：取到最优时的终边
        double[] best = new double[n + 1];
        int[] next = new int[n];
        for (int i = n - 1; i >= 0; i--) {
            double min = Double.POSITIVE_INFINITY;
            int arg = i + 1;
            for (int end : dag.get(i)) {
                double cost = -Math.log((double) effectiveFreq(dict, overlay, sentence.substring(i, end))
                        / dict.totalFreq()) + best[end];
                if (cost < min) {
                    min = cost;
                    arg = end;
                }
            }
            best[i] = min;
            next[i] = arg;
        }
        List<String> words = new ArrayList<>();
        int i = 0;
        while (i < n) {
            int end = next[i];
            words.add(sentence.substring(i, end));
            i = end;
        }
        return List.copyOf(words);
    }

    /** 有效词频：词典词频 > 用户词频 > MIN_FREQ 平滑 */
    public static long effectiveFreq(TrieDict dict, UserDictOverlay overlay, String word) {
        long freq = dict.freq(word);
        if (freq > 0) {
            return freq;
        }
        if (overlay != null) {
            Long userFreq = overlay.userWords().get(word);
            if (userFreq != null) {
                return userFreq;
            }
        }
        return TrieDict.MIN_FREQ;
    }

    /** 切分代价：各词 -log(p) 之和（可解释口径） */
    public double totalCost(TrieDict dict, List<String> words) {
        double sum = 0.0;
        for (String word : words) {
            sum += edgeCost(dict, word);
        }
        return sum;
    }

    /** 单词代价：-log(freq/total)，未登录按 MIN_FREQ 平滑 */
    public double edgeCost(TrieDict dict, String word) {
        long freq = dict.freq(word) > 0 ? dict.freq(word) : TrieDict.MIN_FREQ;
        return -Math.log((double) freq / dict.totalFreq());
    }

    /** 词序列回拼句子（往返等价口径） */
    public String join(List<String> words) {
        StringBuilder sb = new StringBuilder();
        Deque<String> deque = new ArrayDeque<>(words);
        while (!deque.isEmpty()) {
            sb.append(deque.pollFirst());
        }
        return sb.toString();
    }
}
