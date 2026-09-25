package cn.chyuan.ai.domain.arrowkernel.service;

import java.util.Arrays;

/**
 * Validity 位图（工单 0778 CN2，arrow 思想）。
 * 位级置位清位（1=有效 0=空）/空值计数/全空全非空判定/视图切片共享字/越界拒绝。
 */
public final class ValidityBitmap {

    private final long[] words;
    private final int offset;
    private final int length;

    private ValidityBitmap(long[] words, int offset, int length) {
        this.words = words;
        this.offset = offset;
        this.length = length;
    }

    public int length() {
        return length;
    }

    public static Builder builder(int capacity) {
        return new Builder(capacity);
    }

    public static ValidityBitmap allValid(int length) {
        long[] words = new long[wordsFor(length)];
        Arrays.fill(words, -1L);
        return new ValidityBitmap(words, 0, length);
    }

    public static ValidityBitmap allNull(int length) {
        return new ValidityBitmap(new long[wordsFor(length)], 0, length);
    }

    private static int wordsFor(int bits) {
        return (bits + 63) >>> 6;
    }

    public boolean isValid(int i) {
        checkIndex(i);
        return ((words[(offset + i) >>> 6] >>> ((offset + i) & 63)) & 1L) != 0L;
    }

    private void checkIndex(int i) {
        if (i < 0 || i >= length) {
            throw new IllegalArgumentException("位图越界: " + i + "/" + length);
        }
    }

    /** 空值计数 */
    public int nullCount() {
        int nulls = 0;
        for (int i = 0; i < length; i++) {
            if (!isValid(i)) {
                nulls++;
            }
        }
        return nulls;
    }

    /** 快速路径：全非空（计数为零） */
    public boolean noneNull() {
        return nullCount() == 0;
    }

    /** 快速路径：全空 */
    public boolean allNull() {
        return nullCount() == length;
    }

    /** 切片：共享底层字（zero-copy 语义），只读 */
    public ValidityBitmap slice(int start, int len) {
        if (start < 0 || len < 0 || start + len > length) {
            throw new IllegalArgumentException("切片越界: [" + start + "," + len + ")/" + length);
        }
        return new ValidityBitmap(words, offset + start, len);
    }

    /** 视图位打包（IPC 用）：每字节 8 位，低位在前 */
    public byte[] toPackedBits() {
        byte[] out = new byte[(length + 7) >>> 3];
        for (int i = 0; i < length; i++) {
            if (isValid(i)) {
                out[i >>> 3] |= (byte) (1 << (i & 7));
            }
        }
        return out;
    }

    public static ValidityBitmap fromPackedBits(byte[] bits, int length) {
        if (bits.length < ((length + 7) >>> 3)) {
            throw new IllegalArgumentException("位图数据不足");
        }
        long[] words = new long[wordsFor(length)];
        Arrays.fill(words, -1L);
        for (int i = 0; i < length; i++) {
            if (((bits[i >>> 3] >>> (i & 7)) & 1) == 0) {
                words[i >>> 6] &= ~(1L << (i & 63));
            }
        }
        return new ValidityBitmap(words, 0, length);
    }

    /** 可变构建器：追加式登记 + 事后位级置位清位 */
    public static final class Builder {
        private final long[] words;
        private final int capacity;
        private int size = 0;

        Builder(int capacity) {
            if (capacity < 0) {
                throw new IllegalArgumentException("容量为负");
            }
            this.capacity = capacity;
            this.words = new long[wordsFor(capacity)];
            Arrays.fill(words, -1L);
        }

        public Builder appendValid() {
            ensure();
            words[size >>> 6] |= 1L << (size & 63);
            size++;
            return this;
        }

        public Builder appendNull() {
            ensure();
            words[size >>> 6] &= ~(1L << (size & 63));
            size++;
            return this;
        }

        /** 事后置位/清位（仍限已追加范围） */
        public Builder set(int i) {
            check(i);
            words[i >>> 6] |= 1L << (i & 63);
            return this;
        }

        public Builder clear(int i) {
            check(i);
            words[i >>> 6] &= ~(1L << (i & 63));
            return this;
        }

        private void ensure() {
            if (size >= capacity) {
                throw new IllegalStateException("位图容量已满: " + capacity);
            }
        }

        private void check(int i) {
            if (i < 0 || i >= size) {
                throw new IllegalArgumentException("位图索引未登记: " + i);
            }
        }

        public ValidityBitmap build() {
            return new ValidityBitmap(words, 0, size);
        }
    }
}
