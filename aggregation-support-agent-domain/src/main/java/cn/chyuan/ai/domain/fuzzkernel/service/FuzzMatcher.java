package cn.chyuan.ai.domain.fuzzkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 模糊匹配内核（工单 0769-0771/0774 CM1-CM4，fzf 思想）。
 * 有序子序列判定与大小写智能/边界·连续·间隙计分权表（可配）/打分矩阵回溯最优区间/同分靠前短跨/排序归一化 Top-N。
 */
public final class FuzzMatcher {

    /** 计分权表（CM2，构造可配，默认沿 fzf 常量折算） */
    public record Weights(int match, int start, int white, int boundary, int camel,
                          int consecutive, int gapStart, int gapExtend) {

        public static Weights defaults() {
            return new Weights(16, 12, 10, 8, 7, 4, 3, 1);
        }

        public Weights withMatch(int match) {
            return new Weights(match, start, white, boundary, camel, consecutive, gapStart, gapExtend);
        }

        public Weights withBoundary(int boundary) {
            return new Weights(match, start, white, boundary, camel, consecutive, gapStart, gapExtend);
        }

        public Weights withGapStart(int gapStart) {
            return new Weights(match, start, white, boundary, camel, consecutive, gapStart, gapExtend);
        }
    }

    /** 匹配结果：分数与命中位置（升序）/区间 [start,end) */
    public record Result(int score, List<Integer> positions, int start, int end) {

        public double normalized() {
            return (double) score / Math.max(1, positions.size());
        }
    }

    /** 高亮区间 [start,end)（CM6，闭开区间，越界拒绝） */
    public record Range(int start, int end) {

        public Range {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("非法区间: [" + start + "," + end + ")");
            }
        }
    }

    /** 排序候选（CM4）：原文/原始分/归一化分 */
    public record Candidate(String text, int score, double normalized) {
    }

    private final Weights weights;

    public FuzzMatcher() {
        this(Weights.defaults());
    }

    public FuzzMatcher(Weights weights) {
        this.weights = weights;
    }

    public Weights weights() {
        return weights;
    }

    /** 大小写智能：模式含大写则敏感，否则不敏感（CM1） */
    public static boolean caseSensitive(String pattern) {
        return pattern.chars().anyMatch(Character::isUpperCase);
    }

    private boolean charEquals(char p, char t, boolean sensitive) {
        return sensitive ? p == t : Character.toLowerCase(p) == Character.toLowerCase(t);
    }

    /** 有序子序列判定（不回溯最优，仅判定存在性） */
    public boolean isSubsequence(String pattern, String text) {
        boolean sensitive = caseSensitive(pattern);
        int at = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char pc = pattern.charAt(i);
            boolean found = false;
            while (at < text.length()) {
                if (charEquals(pc, text.charAt(at), sensitive)) {
                    found = true;
                    at++;
                    break;
                }
                at++;
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** 位置奖励（CM2）：串首/空白后/非词字符后/驼峰 */
    int bonusAt(String text, int j) {
        if (j == 0) {
            return weights.start();
        }
        char prev = text.charAt(j - 1);
        char cur = text.charAt(j);
        if (Character.isWhitespace(prev)) {
            return weights.white();
        }
        if (!isWord(prev)) {
            return weights.boundary();
        }
        if (Character.isUpperCase(cur) && Character.isLowerCase(prev)) {
            return weights.camel();
        }
        return 0;
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c);
    }

    /** 匹配（CM3）：打分矩阵 D[i][k]=模式前 i+1 字符、第 i 字符落在 k 的最优分；回溯位置；同分靠前/短跨 */
    public Optional<Result> match(String pattern, String text) {
        if (pattern.isEmpty()) {
            return Optional.of(new Result(0, List.of(), 0, 0));
        }
        if (text.isEmpty() || pattern.length() > text.length()) {
            return Optional.empty();
        }
        boolean sensitive = caseSensitive(pattern);
        int n = pattern.length();
        int m = text.length();
        final int NEG = Integer.MIN_VALUE / 4;
        int[][] score = new int[n][m];
        int[][] parent = new int[n][m];
        for (int[] row : score) {
            java.util.Arrays.fill(row, NEG);
        }
        for (int[] row : parent) {
            java.util.Arrays.fill(row, -1);
        }
        for (int k = 0; k < m; k++) {
            if (charEquals(pattern.charAt(0), text.charAt(k), sensitive)) {
                score[0][k] = weights.match() + bonusAt(text, k);
            }
        }
        for (int i = 1; i < n; i++) {
            for (int k = i; k < m; k++) {
                if (!charEquals(pattern.charAt(i), text.charAt(k), sensitive)) {
                    continue;
                }
                int base = weights.match() + bonusAt(text, k);
                int best = NEG;
                int bestH = -1;
                for (int h = i - 1; h < k; h++) {
                    int prev = score[i - 1][h];
                    if (prev == NEG) {
                        continue;
                    }
                    int total = prev + base;
                    if (h == k - 1) {
                        total += weights.consecutive();
                    } else {
                        int gap = k - h - 1;
                        total -= weights.gapStart() + (gap - 1) * weights.gapExtend();
                    }
                    if (total > best) {
                        best = total;
                        bestH = h;
                    }
                }
                score[i][k] = best;
                parent[i][k] = bestH;
            }
        }
        int bestEnd = -1;
        int bestScore = NEG;
        int bestSpan = Integer.MAX_VALUE;
        for (int k = n - 1; k < m; k++) {
            if (score[n - 1][k] == NEG) {
                continue;
            }
            int span = k - firstPosOf(n - 1, k, parent) + 1;
            if (score[n - 1][k] > bestScore
                    || (score[n - 1][k] == bestScore && span < bestSpan)) {
                bestScore = score[n - 1][k];
                bestEnd = k;
                bestSpan = span;
            }
        }
        if (bestEnd < 0) {
            return Optional.empty();
        }
        List<Integer> positions = new ArrayList<>();
        int cur = bestEnd;
        for (int i = n - 1; i >= 0; i--) {
            positions.add(cur);
            cur = parent[i][cur];
        }
        java.util.Collections.reverse(positions);
        int start = positions.get(0);
        return Optional.of(new Result(bestScore, List.copyOf(positions), start, bestEnd + 1));
    }

    private int firstPosOf(int i, int k, int[][] parent) {
        int cur = k;
        while (parent[i][cur] >= 0) {
            cur = parent[i][cur];
            i--;
        }
        return cur;
    }

    /** 排序（CM4）：分数降序/平票字典序/归一化随行/Top-N */
    public List<Candidate> rank(String pattern, List<String> texts) {
        List<Candidate> out = new ArrayList<>();
        for (String text : texts) {
            Optional<Result> r = match(pattern, text);
            r.ifPresent(res -> out.add(new Candidate(text, res.score(), res.normalized())));
        }
        out.sort((a, b) -> {
            if (a.score() != b.score()) {
                return Integer.compare(b.score(), a.score());
            }
            return a.text().compareTo(b.text());
        });
        return out;
    }

    public List<Candidate> rank(String pattern, List<String> texts, int topN) {
        List<Candidate> all = rank(pattern, texts);
        return all.size() <= topN ? all : List.copyOf(all.subList(0, topN));
    }
}
