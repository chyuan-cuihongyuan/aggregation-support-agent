package cn.chyuan.ai.domain.arrowkernel.service;

import java.util.Objects;

/**
 * 列算子（工单 0782 CN6，arrow 思想）。
 * 算术加减乘逐元素（INT64/FP64 提升）/比较六算符生成布尔列/聚合 sum·min·max·count 含空值跳过/类型不匹配拒绝。
 */
public final class ColumnOps {

    public enum Arith { ADD, SUB, MUL }

    public enum Cmp { EQ, NE, LT, LE, GT, GE }

    public enum Agg { SUM, MIN, MAX, COUNT }

    private ColumnOps() {
    }

    private static void requireSameLength(int a, int b) {
        if (a != b) {
            throw new IllegalArgumentException("列长不一致: " + a + " vs " + b);
        }
    }

    /** 算术：INT64×INT64→INT64；含 FP64→FP64；BOOL/UTF8 参与→拒绝；空传播 */
    public static ColumnVector arithmetic(ColumnVector a, ColumnVector b, Arith op) {
        requireSameLength(a.length(), b.length());
        if (a.type() == ArrowSchema.Type.BOOL || a.type() == ArrowSchema.Type.UTF8
                || b.type() == ArrowSchema.Type.BOOL || b.type() == ArrowSchema.Type.UTF8) {
            throw new IllegalArgumentException("算术不支持类型: " + a.type() + "/" + b.type());
        }
        boolean fp = a.type() == ArrowSchema.Type.FP64 || b.type() == ArrowSchema.Type.FP64;
        ColumnVector.Builder out = ColumnVector.builder(fp ? ArrowSchema.Type.FP64 : ArrowSchema.Type.INT64, a.length());
        for (int i = 0; i < a.length(); i++) {
            if (a.isNull(i) || b.isNull(i)) {
                out.appendNull();
                continue;
            }
            if (fp) {
                double x = numOf(a, i);
                double y = numOf(b, i);
                out.appendDouble(apply(x, y, op));
            } else {
                out.appendLong(apply(a.getLong(i), b.getLong(i), op));
            }
        }
        return out.build();
    }

    private static double numOf(ColumnVector v, int i) {
        return v.type() == ArrowSchema.Type.FP64 ? v.getDouble(i) : v.rawLong(v.rawOffset() + i);
    }

    private static long apply(long x, long y, Arith op) {
        return switch (op) {
            case ADD -> x + y;
            case SUB -> x - y;
            case MUL -> x * y;
        };
    }

    private static double apply(double x, double y, Arith op) {
        return switch (op) {
            case ADD -> x + y;
            case SUB -> x - y;
            case MUL -> x * y;
        };
    }

    /** 比较：数值跨整浮/字符串字典序；输出 BOOL 列；空传播 */
    public static ColumnVector compare(ColumnVector a, ColumnVector b, Cmp op) {
        requireSameLength(a.length(), b.length());
        ColumnVector.Builder out = ColumnVector.builder(ArrowSchema.Type.BOOL, a.length());
        for (int i = 0; i < a.length(); i++) {
            if (a.isNull(i) || b.isNull(i)) {
                out.appendNull();
                continue;
            }
            int c;
            if (a.type() == ArrowSchema.Type.UTF8 && b.type() == ArrowSchema.Type.UTF8) {
                throw new IllegalArgumentException("UTF8 比较经 varLen 重载");
            }
            if (a.type() == ArrowSchema.Type.UTF8 || b.type() == ArrowSchema.Type.UTF8) {
                throw new IllegalArgumentException("比较类型不匹配: " + a.type() + "/" + b.type());
            }
            if (a.type() == ArrowSchema.Type.BOOL && b.type() == ArrowSchema.Type.BOOL) {
                boolean x = (Boolean) a.get(i);
                boolean y = (Boolean) b.get(i);
                c = Boolean.compare(x, y);
            } else {
                double x = numOf(a, i);
                double y = numOf(b, i);
                c = Double.compare(x, y);
            }
            out.appendBool(test(c, op));
        }
        return out.build();
    }

    /** UTF8 字典序比较（同型） */
    public static ColumnVector compare(VarLenVector a, VarLenVector b, Cmp op) {
        requireSameLength(a.length(), b.length());
        ColumnVector.Builder out = ColumnVector.builder(ArrowSchema.Type.BOOL, a.length());
        for (int i = 0; i < a.length(); i++) {
            if (a.isNull(i) || b.isNull(i)) {
                out.appendNull();
                continue;
            }
            out.appendBool(test(a.get(i).compareTo(b.get(i)), op));
        }
        return out.build();
    }

    private static boolean test(int c, Cmp op) {
        return switch (op) {
            case EQ -> c == 0;
            case NE -> c != 0;
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
        };
    }

    /** 聚合：空值跳过；COUNT=非空数；空集 SUM→零值、MIN/MAX→null；UTF8 仅 MIN/MAX */
    public static Object aggregate(ColumnVector v, Agg agg) {
        if (agg == Agg.COUNT) {
            return (long) (v.length() - v.validity().nullCount());
        }
        if (v.type() == ArrowSchema.Type.BOOL && agg == Agg.SUM) {
            throw new IllegalArgumentException("BOOL 不支持 SUM");
        }
        Long accL = null;
        Double accD = null;
        String accS = null;
        for (int i = 0; i < v.length(); i++) {
            if (v.isNull(i)) {
                continue;
            }
            switch (v.type()) {
                case INT64 -> {
                    long x = v.getLong(i);
                    accL = fold(agg, accL, x);
                }
                case FP64 -> {
                    double x = v.getDouble(i);
                    accD = accD == null ? x : switch (agg) {
                        case SUM -> accD + x;
                        case MIN -> Math.min(accD, x);
                        default -> Math.max(accD, x);
                    };
                }
                case UTF8 -> {
                    throw new IllegalArgumentException("UTF8 聚合经 varLen 重载");
                }
                case BOOL -> throw new IllegalArgumentException("BOOL 仅支持 MIN/MAX/COUNT");
            }
        }
        if (v.type() == ArrowSchema.Type.FP64) {
            return agg == Agg.SUM ? (accD == null ? 0.0 : accD) : accD;
        }
        return agg == Agg.SUM ? (accL == null ? 0L : accL) : (Object) accL;
    }

    private static Long fold(Agg agg, Long acc, long x) {
        if (acc == null) {
            return x;
        }
        return switch (agg) {
            case SUM -> acc + x;
            case MIN -> Math.min(acc, x);
            default -> Math.max(acc, x);
        };
    }

    /** UTF8 聚合（MIN/MAX 字典序） */
    public static Object aggregate(VarLenVector v, Agg agg) {
        if (agg == Agg.COUNT) {
            return (long) (v.length() - v.validity().nullCount());
        }
        if (agg == Agg.SUM) {
            throw new IllegalArgumentException("UTF8 不支持 SUM");
        }
        String acc = null;
        for (int i = 0; i < v.length(); i++) {
            if (v.isNull(i)) {
                continue;
            }
            String x = v.get(i);
            acc = acc == null ? x : (agg == Agg.MIN ? (x.compareTo(acc) < 0 ? x : acc)
                    : (x.compareTo(acc) > 0 ? x : acc));
        }
        return acc;
    }

    /** 布尔列帮助器（测试与 IPC 用值相等断言辅助） */
    static boolean cellEquals(Object a, Object b) {
        return Objects.equals(a, b);
    }
}
