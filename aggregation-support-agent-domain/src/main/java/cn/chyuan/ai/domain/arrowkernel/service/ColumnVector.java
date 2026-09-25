package cn.chyuan.ai.domain.arrowkernel.service;

import java.util.Arrays;

/**
 * 定宽数值向量（工单 0779 CN3，arrow 思想）。
 * INT64/FP64/BOOL 追加读取/null 位图联动/视图切片共享底层/类型错配拒绝。
 */
public final class ColumnVector {

    private final ArrowSchema.Type type;
    private final long[] longs;
    private final double[] doubles;
    private final boolean[] bools;
    private final ValidityBitmap validity;
    private final int offset;
    private final int length;
    private final boolean view;

    private ColumnVector(ArrowSchema.Type type, long[] longs, double[] doubles, boolean[] bools,
                         ValidityBitmap validity, int offset, int length, boolean view) {
        this.type = type;
        this.longs = longs;
        this.doubles = doubles;
        this.bools = bools;
        this.validity = validity;
        this.offset = offset;
        this.length = length;
        this.view = view;
    }

    public ArrowSchema.Type type() {
        return type;
    }

    public int length() {
        return length;
    }

    public ValidityBitmap validity() {
        return validity;
    }

    public boolean isNull(int i) {
        checkIndex(i);
        return !validity.isValid(offset + i);
    }

    public static Builder builder(ArrowSchema.Type type, int capacity) {
        return new Builder(type, capacity);
    }

    public Object get(int i) {
        if (isNull(i)) {
            return null;
        }
        return switch (type) {
            case INT64 -> longs[offset + i];
            case FP64 -> doubles[offset + i];
            case BOOL -> bools[offset + i];
            default -> throw new IllegalStateException("定宽向量不含 " + type);
        };
    }

    /** 类型化读取：错配拒绝 */
    public long getLong(int i) {
        if (type != ArrowSchema.Type.INT64) {
            throw new IllegalArgumentException("类型错配: 期望 INT64 实际 " + type);
        }
        Object v = get(i);
        if (v == null) {
            throw new IllegalArgumentException("空值无裸读取: " + i);
        }
        return (Long) v;
    }

    public double getDouble(int i) {
        if (type != ArrowSchema.Type.FP64) {
            throw new IllegalArgumentException("类型错配: 期望 FP64 实际 " + type);
        }
        Object v = get(i);
        if (v == null) {
            throw new IllegalArgumentException("空值无裸读取: " + i);
        }
        return (Double) v;
    }

    /** 切片：共享底层数组与位图（zero-copy 语义），只读 */
    public ColumnVector slice(int start, int len) {
        if (start < 0 || len < 0 || start + len > length) {
            throw new IllegalArgumentException("切片越界: [" + start + "," + len + ")/" + length);
        }
        return new ColumnVector(type, longs, doubles, bools, validity, offset + start, len, true);
    }

    long rawLong(int abs) {
        return longs[abs];
    }

    double rawDouble(int abs) {
        return doubles[abs];
    }

    boolean rawBool(int abs) {
        return bools[abs];
    }

    int rawOffset() {
        return offset;
    }

    private void checkIndex(int i) {
        if (i < 0 || i >= length) {
            throw new IllegalArgumentException("向量越界: " + i + "/" + length);
        }
    }

    public static final class Builder {
        private final ArrowSchema.Type type;
        private long[] longs = new long[0];
        private double[] doubles = new double[0];
        private boolean[] bools = new boolean[0];
        private final ValidityBitmap.Builder validity;
        private int size = 0;

        Builder(ArrowSchema.Type type, int capacity) {
            if (type == ArrowSchema.Type.UTF8) {
                throw new IllegalArgumentException("UTF8 用 VarLenVector");
            }
            this.type = type;
            this.validity = ValidityBitmap.builder(capacity);
            switch (type) {
                case INT64 -> longs = new long[capacity];
                case FP64 -> doubles = new double[capacity];
                case BOOL -> bools = new boolean[capacity];
                default -> throw new IllegalStateException();
            }
        }

        public Builder appendLong(long v) {
            requireType(ArrowSchema.Type.INT64);
            longs[size] = v;
            validity.appendValid();
            size++;
            return this;
        }

        public Builder appendDouble(double v) {
            requireType(ArrowSchema.Type.FP64);
            doubles[size] = v;
            validity.appendValid();
            size++;
            return this;
        }

        public Builder appendBool(boolean v) {
            requireType(ArrowSchema.Type.BOOL);
            bools[size] = v;
            validity.appendValid();
            size++;
            return this;
        }

        public Builder appendNull() {
            validity.appendNull();
            size++;
            return this;
        }

        private void requireType(ArrowSchema.Type expected) {
            if (type != expected) {
                throw new IllegalArgumentException("类型错配: 期望 " + expected + " 实际 " + type);
            }
        }

        public ColumnVector build() {
            return new ColumnVector(type, Arrays.copyOf(longs, longs.length), Arrays.copyOf(doubles, doubles.length),
                    Arrays.copyOf(bools, bools.length), validity.build(), 0, size, false);
        }
    }
}
