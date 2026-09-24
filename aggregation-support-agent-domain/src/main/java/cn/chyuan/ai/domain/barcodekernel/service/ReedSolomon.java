package cn.chyuan.ai.domain.barcodekernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Reed-Solomon 纠错（工单 0718 CG3，zxing 思想）。
 * 按纠错字数生成多项式/余数编码/版本分块结构表与交织输出/短块 0 填充/
 * 综合子校验与 Berlekamp-Massey 定位·高斯消元求值纠错（多项式高位在前）。
 */
public final class ReedSolomon {

    private ReedSolomon() {
    }

    /** 生成多项式：(x-α^0)(x-α^1)...(x-α^(degree-1))，高位在前 */
    public static int[] generator(int degree) {
        if (degree <= 0) {
            throw new IllegalArgumentException("纠错字数须 > 0");
        }
        int[] poly = {1};
        for (int i = 0; i < degree; i++) {
            int root = Gf256.exp(i);
            int[] next = new int[poly.length + 1];
            for (int j = 0; j < poly.length; j++) {
                next[j] ^= Gf256.mul(poly[j], 1);
                next[j + 1] ^= Gf256.mul(poly[j], root);
            }
            poly = next;
        }
        return poly;
    }

    /** 余数编码：数据补 degree 个 0 后模生成多项式，返回 degree 个纠错码字 */
    public static int[] encode(int[] data, int degree) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("空数据");
        }
        if (degree <= 0) {
            throw new IllegalArgumentException("纠错字数须 > 0");
        }
        int[] gen = generator(degree);
        int[] buf = new int[data.length + degree];
        System.arraycopy(data, 0, buf, 0, data.length);
        for (int i = 0; i < data.length; i++) {
            int factor = buf[i];
            if (factor != 0) {
                for (int j = 1; j < gen.length; j++) {
                    buf[i + j] ^= Gf256.mul(gen[j], factor);
                }
            }
        }
        int[] ec = new int[degree];
        System.arraycopy(buf, buf.length - degree, ec, 0, degree);
        return ec;
    }

    /** 分块结构：块数/每块数据码字/每块纠错码字 */
    public record BlockSpec(int blocks, int dataPerBlock, int ecPerBlock) {
        public int dataTotal() {
            return blocks * dataPerBlock;
        }
    }

    /** 交织输出：数据块逐位轮转 + 纠错块逐位轮转 */
    public static int[] interleave(List<int[]> dataBlocks, List<int[]> ecBlocks) {
        if (dataBlocks.size() != ecBlocks.size() || dataBlocks.isEmpty()) {
            throw new IllegalArgumentException("块数不一致或为空");
        }
        int dataLen = dataBlocks.get(0).length;
        int ecLen = ecBlocks.get(0).length;
        for (int[] b : dataBlocks) {
            if (b.length != dataLen) {
                throw new IllegalArgumentException("数据块长度不齐");
            }
        }
        int[] out = new int[dataLen * dataBlocks.size() + ecLen * ecBlocks.size()];
        int at = 0;
        for (int i = 0; i < dataLen; i++) {
            for (int[] block : dataBlocks) {
                out[at++] = block[i];
            }
        }
        for (int i = 0; i < ecLen; i++) {
            for (int[] block : ecBlocks) {
                out[at++] = block[i];
            }
        }
        return out;
    }

    /** 交织还原：按分块结构拆回「数据+纠错」整块 */
    public static List<int[]> deinterleave(int[] codewords, BlockSpec spec) {
        if (codewords.length != spec.dataTotal() + spec.blocks * spec.ecPerBlock) {
            throw new IllegalArgumentException("码字总数与分块结构不符");
        }
        List<int[]> blocks = new ArrayList<>();
        for (int b = 0; b < spec.blocks; b++) {
            blocks.add(new int[spec.dataPerBlock + spec.ecPerBlock]);
        }
        int at = 0;
        for (int i = 0; i < spec.dataPerBlock; i++) {
            for (int[] block : blocks) {
                block[i] = codewords[at++];
            }
        }
        for (int i = 0; i < spec.ecPerBlock; i++) {
            for (int[] block : blocks) {
                block[spec.dataPerBlock + i] = codewords[at++];
            }
        }
        return blocks;
    }

    /** 综合子：S_j = 块多项式在 α^j 的取值（高位在前求值）；全零即无错 */
    public static int[] syndromes(int[] blockHighFirst, int ecLen) {
        int[] syn = new int[ecLen];
        for (int j = 0; j < ecLen; j++) {
            syn[j] = polyEval(blockHighFirst, Gf256.exp(j));
        }
        return syn;
    }

    /** 纠错：返回修复后的块；超能力抛 IllegalStateException（不可纠拒绝） */
    public static int[] correct(int[] blockHighFirst, int ecLen) {
        int n = blockHighFirst.length;
        int[] syn = syndromes(blockHighFirst, ecLen);
        boolean clean = true;
        for (int s : syn) {
            if (s != 0) {
                clean = false;
                break;
            }
        }
        if (clean) {
            return blockHighFirst.clone();
        }
        int[] errLoc = errorLocator(syn, ecLen);
        int errors = errLoc.length - 1;
        // Chien 搜索定位：Λ=∏(1+X_k x) 根在 X_k^{-1}=α^{-(n-1-p)}，故在 α^{-i} 处求值
        int[] positions = new int[errors];
        int found = 0;
        for (int i = 0; i < n; i++) {
            int x = Gf256.exp(i == 0 ? 0 : 255 - i);
            if (polyEval(errLoc, x) == 0) {
                if (found == errors) {
                    throw new IllegalStateException("RS 不可纠：定位数超估计");
                }
                positions[found++] = n - 1 - i;
            }
        }
        if (found != errors) {
            throw new IllegalStateException("RS 不可纠：定位不全");
        }
        // 高斯消元求错误幅值：Σ_k e_k·α^(j·(n-1-pos_k)) = S_j
        int[][] a = new int[ecLen][errors];
        for (int j = 0; j < ecLen; j++) {
            for (int k = 0; k < errors; k++) {
                a[j][k] = Gf256.exp(j * (n - 1 - positions[k]));
            }
        }
        int[] values = solve(a, syn, ecLen, errors);
        int[] fixed = blockHighFirst.clone();
        for (int k = 0; k < errors; k++) {
            fixed[positions[k]] ^= values[k];
        }
        for (int s : syndromes(fixed, ecLen)) {
            if (s != 0) {
                throw new IllegalStateException("RS 不可纠：修正后校验仍非零");
            }
        }
        return fixed;
    }

    /** Berlekamp-Massey 错误定位多项式（高位在前），返回其系数 */
    private static int[] errorLocator(int[] syn, int ecLen) {
        List<Integer> errLoc = new ArrayList<>(List.of(1));
        List<Integer> oldLoc = new ArrayList<>(List.of(1));
        for (int i = 0; i < ecLen; i++) {
            int delta = syn[i];
            for (int j = 1; j < errLoc.size(); j++) {
                int idx = i - j;
                if (idx >= 0) {
                    delta ^= Gf256.mul(errLoc.get(errLoc.size() - 1 - j), syn[idx]);
                }
            }
            oldLoc.add(0);
            if (delta != 0) {
                if (oldLoc.size() > errLoc.size()) {
                    List<Integer> newLoc = scale(oldLoc, delta);
                    oldLoc = scale(errLoc, Gf256.inverse(delta));
                    errLoc = newLoc;
                }
                errLoc = polyAdd(errLoc, scale(oldLoc, delta));
            }
        }
        while (errLoc.size() > 1 && errLoc.get(0) == 0) {
            errLoc.remove(0);
        }
        int[] out = new int[errLoc.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = errLoc.get(i);
        }
        if ((out.length - 1) * 2 > ecLen) {
            throw new IllegalStateException("RS 不可纠：错误数超能力");
        }
        return out;
    }

    /** 高位在前多项式求值（Horner） */
    public static int polyEval(int[] poly, int x) {
        int y = poly[0];
        for (int i = 1; i < poly.length; i++) {
            y = Gf256.mul(y, x) ^ poly[i];
        }
        return y;
    }

    private static List<Integer> scale(List<Integer> poly, int x) {
        List<Integer> out = new ArrayList<>(poly.size());
        for (int c : poly) {
            out.add(Gf256.mul(c, x));
        }
        return out;
    }

    private static List<Integer> polyAdd(List<Integer> a, List<Integer> b) {
        List<Integer> out = new ArrayList<>(Math.max(a.size(), b.size()));
        int diff = Math.abs(a.size() - b.size());
        for (int i = 0; i < diff; i++) {
            out.add(a.size() >= b.size() ? a.get(i) : b.get(i));
        }
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            out.add(a.get(a.size() - Math.min(a.size(), b.size()) + i)
                    ^ b.get(b.size() - Math.min(a.size(), b.size()) + i));
        }
        return out;
    }

    /** GF(256) 高斯消元解 A·x = b（行变换消元 + 回代） */
    private static int[] solve(int[][] a, int[] b, int rows, int cols) {
        int[] x = new int[cols];
        int[] where = new int[cols];
        java.util.Arrays.fill(where, -1);
        for (int col = 0, row = 0; col < cols && row < rows; col++) {
            int pivot = row;
            for (int i = row; i < rows; i++) {
                if (a[i][col] != 0) {
                    pivot = i;
                    break;
                }
            }
            if (a[pivot][col] == 0) {
                continue;
            }
            int[] tmp = a[row];
            a[row] = a[pivot];
            a[pivot] = tmp;
            int tb = b[row];
            b[row] = b[pivot];
            b[pivot] = tb;
            int inv = Gf256.inverse(a[row][col]);
            for (int j = col; j < cols; j++) {
                a[row][j] = Gf256.mul(a[row][j], inv);
            }
            b[row] = Gf256.mul(b[row], inv);
            for (int i = 0; i < rows; i++) {
                if (i != row && a[i][col] != 0) {
                    int f = a[i][col];
                    for (int j = col; j < cols; j++) {
                        a[i][j] ^= Gf256.mul(f, a[row][j]);
                    }
                    b[i] ^= Gf256.mul(f, b[row]);
                }
            }
            where[col] = row;
            row++;
        }
        for (int col = 0; col < cols; col++) {
            if (where[col] < 0) {
                throw new IllegalStateException("RS 不可纠：幅值方程欠定");
            }
            x[col] = b[where[col]];
        }
        return x;
    }
}
