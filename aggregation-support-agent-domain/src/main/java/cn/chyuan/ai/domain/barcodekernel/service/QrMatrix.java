package cn.chyuan.ai.domain.barcodekernel.service;

/**
 * 矩阵放置（工单 0719 CG4，zxing 思想）。
 * finder/separator/timing/alignment/dark module/格式信息 BCH15/版本信息 BCH18（≥7）/
 * 数据 zigzag 双列布位/保留区跳过。
 */
public final class QrMatrix {

    public final int version;
    public final int size;
    private final boolean[][] dark;
    private final boolean[][] reserved;

    public QrMatrix(int version) {
        if (version < 1 || version > 4) {
            throw new IllegalArgumentException("版本超子集（1-4）: " + version);
        }
        this.version = version;
        this.size = 17 + 4 * version;
        this.dark = new boolean[size][size];
        this.reserved = new boolean[size][size];
        placeFunctionPatterns();
    }

    private void placeFunctionPatterns() {
        // finder + separator：三角落
        placeFinder(0, 0);
        placeFinder(0, size - 7);
        placeFinder(size - 7, 0);
        // timing：第 6 行/列交替
        for (int i = 8; i < size - 8; i++) {
            setReserved(6, i, i % 2 == 0);
            setReserved(i, 6, i % 2 == 0);
        }
        // alignment：v2-4 单个（不与 finder 重叠）
        if (version >= 2) {
            int c = 10 + 4 * version;
            placeAlignment(c, c);
        }
        // dark module
        setReserved(4 * version + 9, 8, true);
        // 格式信息区预留
        for (int i = 0; i <= 8; i++) {
            if (i != 6) {
                reserve(8, i);
                reserve(i, 8);
            }
        }
        reserve(8, size - 8);
        for (int i = size - 8; i < size; i++) {
            reserve(i, 8);
            reserve(8, i);
        }
    }

    private void placeFinder(int row, int col) {
        for (int r = -1; r <= 7; r++) {
            for (int c = -1; c <= 7; c++) {
                int rr = row + r;
                int cc = col + c;
                if (rr < 0 || rr >= size || cc < 0 || cc >= size) {
                    continue;
                }
                boolean isDark = r >= 0 && r <= 6 && c >= 0 && c <= 6
                        && (r == 0 || r == 6 || c == 0 || c == 6 || (r >= 2 && r <= 4 && c >= 2 && c <= 4));
                setReserved(rr, cc, isDark);
            }
        }
    }

    private void placeAlignment(int centerRow, int centerCol) {
        for (int r = -2; r <= 2; r++) {
            for (int c = -2; c <= 2; c++) {
                boolean isDark = Math.max(Math.abs(r), Math.abs(c)) != 1;
                setReserved(centerRow + r, centerCol + c, isDark);
            }
        }
    }

    private void reserve(int row, int col) {
        reserved[row][col] = true;
    }

    private void setReserved(int row, int col, boolean isDark) {
        reserved[row][col] = true;
        dark[row][col] = isDark;
    }

    public boolean isReserved(int row, int col) {
        return reserved[row][col];
    }

    public boolean isDark(int row, int col) {
        return dark[row][col];
    }

    /** 设置格式信息（15 bit，掩码后），占两处冗余位置 */
    public void setFormatInfo(int ecBits, int maskId) {
        int data = (ecBits << 3) | maskId;
        int bch = bch15(data) ^ 0x5412;
        int bit = 0;
        // 左上第一份：f0→(8,0)...f14→(0,8)
        int[][] first = {{8, 0}, {8, 1}, {8, 2}, {8, 3}, {8, 4}, {8, 5}, {8, 7}, {8, 8},
                {7, 8}, {5, 8}, {4, 8}, {3, 8}, {2, 8}, {1, 8}, {0, 8}};
        for (int i = 14; i >= 0; i--) {
            dark[first[bit][0]][first[bit][1]] = ((bch >> i) & 1) == 1;
            bit++;
        }
        // 右/下第二份
        for (int i = 0; i < 8; i++) {
            dark[size - 1 - i][8] = ((bch >> (14 - i)) & 1) == 1;
        }
        for (int i = 8; i < 15; i++) {
            dark[8][size - 15 + i] = ((bch >> (14 - i)) & 1) == 1;
        }
    }

    /** 格式信息 BCH(15,5) 余数：x^10 乘积 mod 0x537 */
    public static int bch15(int data5) {
        if (data5 < 0 || data5 > 31) {
            throw new IllegalArgumentException("格式数据 5 bit 越界: " + data5);
        }
        int value = data5 << 10;
        int generator = 0x537;
        for (int i = 14; i >= 10; i--) {
            if (((value >> i) & 1) == 1) {
                value ^= generator << (i - 10);
            }
        }
        return (data5 << 10) | value;
    }

    /** 版本信息 BCH(18,6)（≥7 版本使用，本子集仅校验位计算） */
    public static int bch18(int version) {
        if (version < 1 || version > 40) {
            throw new IllegalArgumentException("非法版本: " + version);
        }
        int value = version << 12;
        int generator = 0x1f25;
        for (int i = 17; i >= 12; i--) {
            if (((value >> i) & 1) == 1) {
                value ^= generator << (i - 12);
            }
        }
        return (version << 12) | value;
    }

    /** zigzag 双列布位：数据位填入非保留模块 */
    public void placeData(int[] codewords) {
        int totalBits = codewords.length * 8;
        int bitAt = 0;
        boolean up = true;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                int row = up ? size - 1 - vert : vert;
                for (int col = right; col >= right - 1; col--) {
                    if (!reserved[row][col]) {
                        boolean bit = bitAt < totalBits && ((codewords[bitAt / 8] >> (7 - bitAt % 8)) & 1) == 1;
                        dark[row][col] = bit;
                        bitAt++;
                    }
                }
            }
            up = !up;
        }
        if (bitAt < totalBits) {
            throw new IllegalArgumentException("矩阵容量不足: " + bitAt + "/" + totalBits);
        }
    }

    /** 数据位收集（解码用）：按布位序返回非保留模块位 */
    public int[] collectData() {
        java.util.List<Integer> bits = new java.util.ArrayList<>();
        boolean up = true;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                int row = up ? size - 1 - vert : vert;
                for (int col = right; col >= right - 1; col--) {
                    if (reserved[row][col]) {
                        continue;
                    }
                    bits.add(dark[row][col] ? 1 : 0);
                }
            }
            up = !up;
        }
        int[] out = new int[bits.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = bits.get(i);
        }
        return out;
    }

    /** 掩码应用于数据区（不触碰功能图案） */
    public void applyMask(int maskId) {
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (!reserved[row][col] && Masking.isMasked(maskId, row, col)) {
                    dark[row][col] ^= true;
                }
            }
        }
    }

    public boolean[][] modules() {
        boolean[][] copy = new boolean[size][size];
        for (int i = 0; i < size; i++) {
            System.arraycopy(dark[i], 0, copy[i], 0, size);
        }
        return copy;
    }

    /** 从已填矩阵重建（解码用）：重画功能图案以获得保留图 */
    public static QrMatrix scaffold(int version) {
        return new QrMatrix(version);
    }

    public void setDark(int row, int col, boolean value) {
        dark[row][col] = value;
    }
}
