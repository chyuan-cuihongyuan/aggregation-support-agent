package cn.chyuan.ai.domain.compresskernel.service;

import java.util.HashSet;
import java.util.Set;

/**
 * 压缩级别档位（工单 0591 BR6，zstd level 思想，配置驱动自进化落点）。
 * 级别参数（窗口大小/哈希链深/懒惰匹配/最小匹配长度随级别单调）/
 * 压缩比与耗时统计口径/按内容特征（重复率）选级策略/非法级别拒绝。
 */
public final class CompressionLevels {

    /** 档位参数：窗口/链深/懒惰/最小匹配长度 */
    public record Params(int windowSize, int maxChain, boolean lazy, int minMatch, int offsetBits) {
    }

    /** 档位级数范围 */
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 9;

    private CompressionLevels() {
    }

    /** 取档位参数（级别单调：窗口与链深随级别不降） */
    public static Params of(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("级别须在 " + MIN_LEVEL + "-" + MAX_LEVEL + "：" + level);
        }
        int windowKb = Math.min(32, level * 4);
        int window = windowKb * 1024;
        int chain = Math.max(4, level * 14);
        boolean lazy = level >= 4;
        int minMatch = level >= 6 ? 4 : 3;
        return new Params(window, chain, lazy, minMatch, bitsFor(window));
    }

    /** 内容自适应选级：重复 4-gram 比率高则高档 */
    public static int suggestLevel(byte[] sample) {
        if (sample == null) {
            throw new IllegalArgumentException("样本不得为 null");
        }
        double ratio = repeatRatio(sample);
        if (ratio > 0.6d) {
            return 9;
        }
        if (ratio > 0.3d) {
            return 6;
        }
        if (ratio > 0.1d) {
            return 3;
        }
        return 1;
    }

    /** 重复率：前 4KB 内 4-gram 重复出现位置占比 */
    public static double repeatRatio(byte[] sample) {
        int window = Math.min(sample.length, 4096);
        if (window < 8) {
            return 0d;
        }
        Set<Long> seen = new HashSet<>();
        int repeated = 0;
        int total = 0;
        for (int i = 0; i + 4 <= window; i++) {
            long gram = ((sample[i] & 0xFFL) << 24) | ((sample[i + 1] & 0xFFL) << 16)
                    | ((sample[i + 2] & 0xFFL) << 8) | (sample[i + 3] & 0xFFL);
            total++;
            if (!seen.add(gram)) {
                repeated++;
            }
        }
        return total == 0 ? 0d : (double) repeated / total;
    }

    /** 窗口值的偏移位宽（编码 offset-1 所需位数） */
    public static int bitsFor(int windowSize) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(1, windowSize - 1));
    }
}
