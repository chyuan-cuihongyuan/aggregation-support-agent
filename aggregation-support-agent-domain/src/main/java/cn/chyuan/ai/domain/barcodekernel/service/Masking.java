package cn.chyuan.ai.domain.barcodekernel.service;

/**
 * 掩码（工单 0720 CG5，zxing 思想）。
 * 8 掩码公式/掩码仅作用数据区/N1-N4 四罚分规则/总分选优确定性平票取小。
 */
public final class Masking {

    private Masking() {
    }

    /** 掩码公式：0-7 */
    public static boolean isMasked(int maskId, int row, int col) {
        return switch (maskId) {
            case 0 -> (row + col) % 2 == 0;
            case 1 -> row % 2 == 0;
            case 2 -> col % 3 == 0;
            case 3 -> (row + col) % 3 == 0;
            case 4 -> (row / 2 + col / 3) % 2 == 0;
            case 5 -> (row * col) % 2 + (row * col) % 3 == 0;
            case 6 -> ((row * col) % 2 + (row * col) % 3) % 2 == 0;
            case 7 -> ((row + col) % 2 + (row * col) % 3) % 2 == 0;
            default -> throw new IllegalArgumentException("非法掩码: " + maskId);
        };
    }

    /** 罚分总分（N1-N4 之和） */
    public static int penalty(boolean[][] modules) {
        return n1(modules) + n2(modules) + n3(modules) + n4(modules);
    }

    /** N1：行/列同色连续 ≥5 → 3+(长度-5) */
    static int n1(boolean[][] m) {
        int n = m.length;
        int total = 0;
        for (int i = 0; i < n; i++) {
            total += runs(m[i]);
            total += runs(column(m, i));
        }
        return total;
    }

    private static int runs(boolean[] line) {
        int total = 0;
        int run = 1;
        for (int i = 1; i < line.length; i++) {
            if (line[i] == line[i - 1]) {
                run++;
            } else {
                if (run >= 5) {
                    total += 3 + run - 5;
                }
                run = 1;
            }
        }
        if (run >= 5) {
            total += 3 + run - 5;
        }
        return total;
    }

    private static boolean[] column(boolean[][] m, int col) {
        boolean[] out = new boolean[m.length];
        for (int i = 0; i < m.length; i++) {
            out[i] = m[i][col];
        }
        return out;
    }

    /** N2：2×2 同色块每个 +3 */
    static int n2(boolean[][] m) {
        int total = 0;
        for (int i = 0; i < m.length - 1; i++) {
            for (int j = 0; j < m.length - 1; j++) {
                if (m[i][j] == m[i][j + 1] && m[i][j] == m[i + 1][j] && m[i][j] == m[i + 1][j + 1]) {
                    total += 3;
                }
            }
        }
        return total;
    }

    /** N3：1011101 图案任一侧带 4 个浅色 → 每个 +40（行/列 11 模窗） */
    static int n3(boolean[][] m) {
        int total = 0;
        for (int i = 0; i < m.length; i++) {
            total += patternHits(m[i]);
            total += patternHits(column(m, i));
        }
        return total;
    }

    private static int patternHits(boolean[] line) {
        // 图案：dark light dark dark dark light dark + 一侧 4 light
        int hits = 0;
        for (int i = 0; i + 11 <= line.length; i++) {
            boolean left4 = !line[i] && !line[i + 1] && !line[i + 2] && !line[i + 3];
            boolean pattern = line[i + 4] && !line[i + 5] && line[i + 6] && line[i + 7] && line[i + 8]
                    && !line[i + 9] && line[i + 10];
            boolean right4 = !line[i + 7] && !line[i + 8] && !line[i + 9] && !line[i + 10];
            boolean leftPattern = line[i] && !line[i + 1] && line[i + 2] && line[i + 3] && line[i + 4]
                    && !line[i + 5] && line[i + 6];
            if ((left4 && pattern) || (right4 && leftPattern)) {
                hits++;
            }
        }
        return hits * 40;
    }

    /** N4：暗模块占比偏离 50% → 10×floor(|%-50|/5) */
    static int n4(boolean[][] m) {
        int dark = 0;
        int total = m.length * m.length;
        for (boolean[] row : m) {
            for (boolean cell : row) {
                if (cell) {
                    dark++;
                }
            }
        }
        int percent = dark * 100 / total;
        return Math.abs(percent - 50) / 5 * 10;
    }

    /** 最优掩码：8 个掩码逐一罚分取最小，平票取小 id */
    public static int bestMask(QrMatrix matrix) {
        int best = -1;
        int bestPenalty = Integer.MAX_VALUE;
        for (int maskId = 0; maskId < 8; maskId++) {
            QrMatrix trial = copyOf(matrix);
            trial.applyMask(maskId);
            int p = penalty(trial.modules());
            if (p < bestPenalty) {
                bestPenalty = p;
                best = maskId;
            }
        }
        return best;
    }

    private static QrMatrix copyOf(QrMatrix matrix) {
        QrMatrix copy = new QrMatrix(matrix.version);
        boolean[][] src = matrix.modules();
        for (int i = 0; i < matrix.size; i++) {
            for (int j = 0; j < matrix.size; j++) {
                copy.setDark(i, j, src[i][j]);
            }
        }
        return copy;
    }
}
