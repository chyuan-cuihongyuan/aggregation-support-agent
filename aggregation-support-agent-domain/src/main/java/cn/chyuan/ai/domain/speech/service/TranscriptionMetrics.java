package cn.chyuan.ai.domain.speech.service;

import java.util.List;

/**
 * 转写质量指标（工单 0385 AU7）。
 * 参考/假设词序列 → WER（词错误率：编辑距离对齐 S/D/I 操作计数）与 CER（字符级）。纯函数。
 */
public class TranscriptionMetrics {

    /** 指标结果 */
    public record Result(int substitutions, int deletions, int insertions, double wer, double cer) {
    }

    /**
     * 计算 WER/CER。空参考：假设亦空 → 全对（0）；假设非空 → 全错（1.0）。
     */
    public Result compute(List<String> reference, List<String> hypothesis) {
        List<String> ref = reference == null ? List.of() : reference;
        List<String> hyp = hypothesis == null ? List.of() : hypothesis;
        long[] ops = editDistanceOps(ref, hyp);
        int sub = (int) ops[0];
        int del = (int) ops[1];
        int ins = (int) ops[2];
        double wer = ref.isEmpty() ? (hyp.isEmpty() ? 0.0 : 1.0)
                : round((double) (sub + del + ins) / ref.size());
        // CER：字符级（词连接后按字符）
        List<String> refChars = toChars(String.join("", ref));
        List<String> hypChars = toChars(String.join("", hyp));
        long[] charOps = editDistanceOps(refChars, hypChars);
        double cer = refChars.isEmpty() ? (hypChars.isEmpty() ? 0.0 : 1.0)
                : round((double) (charOps[0] + charOps[1] + charOps[2]) / refChars.size());
        return new Result(sub, del, ins, wer, cer);
    }

    /** 编辑距离对齐：返回 {替换数, 删除数, 插入数}（一种最优路径） */
    private long[] editDistanceOps(List<String> ref, List<String> hyp) {
        int n = ref.size();
        int m = hyp.size();
        // dp[i][j][0..2] = 距离与操作计数；简化为距离矩阵 + 回溯计数
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (ref.get(i - 1).equals(hyp.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
                }
            }
        }
        // 回溯统计操作
        long sub = 0;
        long del = 0;
        long ins = 0;
        int i = n;
        int j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && ref.get(i - 1).equals(hyp.get(j - 1))) {
                i--;
                j--;
            } else if (i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1) {
                sub++;
                i--;
                j--;
            } else if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
                del++;
                i--;
            } else {
                ins++;
                j--;
            }
        }
        return new long[]{sub, del, ins};
    }

    private List<String> toChars(String text) {
        return text.chars().mapToObj(c -> String.valueOf((char) c)).toList();
    }

    private static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }
}
