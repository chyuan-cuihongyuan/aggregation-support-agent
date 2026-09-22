package cn.chyuan.ai.domain.segkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * HMM 未知词识别（工单 0527 BK3，jieba BMES Viterbi 思想）。
 * BMES 四状态（B 词首/M 词中/E 词尾/S 单字词）内置小参数集
 * （转移偏好 2-4 字成词），发射均匀——解码由转移结构驱动；
 * Viterbi 最优路径须合法终止于 E/S。
 */
public final class HmmSegmenter {

    /** 状态编号 */
    public static final int B = 0;
    public static final int M = 1;
    public static final int E = 2;
    public static final int S = 3;

    /** 片段内成词区间（start 排他含 end） */
    public record Span(int start, int end) {
    }

    // start：B 起始占优（词偏好），S 次之
    private static final double[] LOG_START = {Math.log(0.62), -23.0, -23.0, Math.log(0.38)};
    // trans[from][to]：B→E 成二字词占优，B→M/M→E 支撑长词，E→B/S 衔接下词，S→S 链单字
    private static final double[][] LOG_TRANS = {
            {Double.NEGATIVE_INFINITY, Math.log(0.34), Math.log(0.65), Double.NEGATIVE_INFINITY},
            {-23.0, Math.log(0.38), Math.log(0.60), -23.0},
            {Math.log(0.45), -23.0, -23.0, Math.log(0.55)},
            {Math.log(0.60), -23.0, -23.0, Math.log(0.40)}
    };
    private static final double LOG_EMIT = Math.log(0.25);

    /**
     * 对词典外字符片段做 BMES Viterbi 解码，产出成词区间（升序、互叠不允许）。
     * 长度≥2 倾向合并成词，长度 1 仅能为单字词 S。
     */
    public List<Span> decode(String slice) {
        if (slice == null) {
            throw new IllegalArgumentException("片段不得为 null");
        }
        int n = slice.length();
        if (n == 0) {
            return List.of();
        }
        double[][] score = new double[n][4];
        int[][] back = new int[n][4];
        for (int s = 0; s < 4; s++) {
            score[0][s] = LOG_START[s] + LOG_EMIT;
        }
        for (int t = 1; t < n; t++) {
            for (int to = 0; to < 4; to++) {
                double best = Double.NEGATIVE_INFINITY;
                int arg = -1;
                for (int from = 0; from < 4; from++) {
                    double v = score[t - 1][from] + LOG_TRANS[from][to] + LOG_EMIT;
                    if (v > best) {
                        best = v;
                        arg = from;
                    }
                }
                score[t][to] = best;
                back[t][to] = arg;
            }
        }
        // 合法终止：E 或 S
        int last = score[n - 1][E] >= score[n - 1][S] ? E : S;
        int[] states = new int[n];
        states[n - 1] = last;
        for (int t = n - 1; t > 0; t--) {
            states[t - 1] = back[t][states[t]];
        }
        List<Span> spans = new ArrayList<>();
        int i = 0;
        while (i < n) {
            if (states[i] == S) {
                spans.add(new Span(i, i + 1));
                i++;
            } else {
                int j = i + 1;
                while (j < n && states[j] == M) {
                    j++;
                }
                // states[j] == E 收词
                spans.add(new Span(i, j + 1));
                i = j + 1;
            }
        }
        return List.copyOf(spans);
    }

    /**
     * 词典词区间与 HMM 片段有序合并：hmmSpans 仅覆盖未被词典覆盖的字符位，
     * 输出按起点升序且不重叠；发现重叠拒绝（调用方负责切片对齐）。
     */
    public List<Span> merge(List<Span> dictSpans, List<Span> hmmSpans, int sliceLength) {
        List<int[]> all = new ArrayList<>();
        for (Span s : dictSpans) {
            all.add(new int[]{s.start(), s.end()});
        }
        for (Span s : hmmSpans) {
            all.add(new int[]{s.start(), s.end()});
        }
        all.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        List<Span> merged = new ArrayList<>();
        int cursor = 0;
        for (int[] span : all) {
            if (span[0] < cursor || span[1] > sliceLength) {
                throw new IllegalArgumentException("区间重叠或越界: [" + span[0] + "," + span[1] + ")");
            }
            merged.add(new Span(span[0], span[1]));
            cursor = span[1];
        }
        return List.copyOf(merged);
    }
}
