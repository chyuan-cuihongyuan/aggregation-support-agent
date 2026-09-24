package cn.chyuan.ai.domain.barcodekernel.service;

/**
 * GF(256) 有限域（工单 0717 CG2，zxing 思想）。
 * 本原多项式 0x11d 生成 exp/log 对数表/加减乘除四则/多项式求值乘法/非法参数拒绝。
 */
public final class Gf256 {

    public static final int PRIMITIVE = 0x11d;
    public static final int SIZE = 256;

    private static final int[] EXP = new int[SIZE * 2];
    private static final int[] LOG = new int[SIZE];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if (x >= SIZE) {
                x ^= PRIMITIVE;
            }
        }
        for (int i = 255; i < EXP.length; i++) {
            EXP[i] = EXP[i - 255];
        }
    }

    private Gf256() {
    }

    /** 加法 = 减法 = 异或 */
    public static int add(int a, int b) {
        check(a);
        check(b);
        return a ^ b;
    }

    public static int mul(int a, int b) {
        check(a);
        check(b);
        if (a == 0 || b == 0) {
            return 0;
        }
        return EXP[LOG[a] + LOG[b]];
    }

    public static int div(int a, int b) {
        check(a);
        check(b);
        if (b == 0) {
            throw new ArithmeticException("GF(256) 除零");
        }
        if (a == 0) {
            return 0;
        }
        return EXP[(LOG[a] + 255 - LOG[b]) % 255];
    }

    public static int exp(int power) {
        if (power < 0) {
            throw new IllegalArgumentException("负幂次: " + power);
        }
        return EXP[power % 255];
    }

    public static int log(int a) {
        check(a);
        if (a == 0) {
            throw new ArithmeticException("log(0) 无定义");
        }
        return LOG[a];
    }

    public static int inverse(int a) {
        check(a);
        if (a == 0) {
            throw new ArithmeticException("逆元(0) 无定义");
        }
        return EXP[255 - LOG[a]];
    }

    /** 多项式乘法（系数低位在前） */
    public static int[] polyMul(int[] a, int[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            throw new IllegalArgumentException("空多项式");
        }
        int[] out = new int[a.length + b.length - 1];
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < b.length; j++) {
                out[i + j] ^= mul(a[i], b[j]);
            }
        }
        return out;
    }

    /** 多项式在 x=exp(i) 处求值（系数低位在前） */
    public static int polyEval(int[] poly, int i) {
        if (poly == null || poly.length == 0) {
            throw new IllegalArgumentException("空多项式");
        }
        int y = poly[0];
        for (int j = 1; j < poly.length; j++) {
            y = mul(poly[j], exp(i * j)) ^ y;
        }
        return y;
    }

    private static void check(int v) {
        if (v < 0 || v >= SIZE) {
            throw new IllegalArgumentException("GF(256) 元素越界: " + v);
        }
    }
}
