package cn.chyuan.ai.domain.arrowkernel.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 变长 UTF8 向量（工单 0780 CN4，arrow 思想）。
 * offset 数组+数据区双层布局/追加读取/空串与 null 区分/offset 单调校验/视图切片。
 */
public final class VarLenVector {

    private final byte[] data;
    private final int[] offsets;
    private final int offsetBase;
    private final ValidityBitmap validity;
    private final int offset;
    private final int length;
    private final boolean view;

    private VarLenVector(byte[] data, int[] offsets, int offsetBase, ValidityBitmap validity,
                         int offset, int length, boolean view) {
        this.data = data;
        this.offsets = offsets;
        this.offsetBase = offsetBase;
        this.validity = validity;
        this.offset = offset;
        this.length = length;
        this.view = view;
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

    private void checkIndex(int i) {
        if (i < 0 || i >= length) {
            throw new IllegalArgumentException("向量越界: " + i + "/" + length);
        }
    }

    public static Builder builder(int capacity) {
        return new Builder(capacity);
    }

    /** 读取：null 返回 null；空串与 null 语义区分（valid 且 offset 相邻） */
    public String get(int i) {
        if (isNull(i)) {
            return null;
        }
        int from = offsets[offsetBase + offset + i];
        int to = offsets[offsetBase + offset + i + 1];
        return new String(data, from, to - from, StandardCharsets.UTF_8);
    }

    /** 视图切片：共享数据区与 offsets（zero-copy 语义） */
    public VarLenVector slice(int start, int len) {
        if (start < 0 || len < 0 || start + len > length) {
            throw new IllegalArgumentException("切片越界: [" + start + "," + len + ")/" + length);
        }
        return new VarLenVector(data, offsets, offsetBase + offset + start, validity.slice(offset + start, len),
                0, len, true);
    }

    int rawOffsetAt(int viewIndex) {
        return offsets[offsetBase + offset + viewIndex];
    }

    public static final class Builder {
        private final java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        private final java.util.List<Integer> ends = new java.util.ArrayList<>();
        private final ValidityBitmap.Builder validity;
        private int lastEnd = 0;

        Builder(int capacity) {
            this.validity = ValidityBitmap.builder(capacity);
            this.ends.add(0);
        }

        public Builder appendString(String v) {
            byte[] bytes = v.getBytes(StandardCharsets.UTF_8);
            data.writeBytes(bytes);
            lastEnd += bytes.length;
            ends.add(lastEnd);
            validity.appendValid();
            return this;
        }

        public Builder appendNull() {
            ends.add(lastEnd);
            validity.appendNull();
            return this;
        }

        /** offset 单调校验后出向量 */
        public VarLenVector build() {
            for (int i = 1; i < ends.size(); i++) {
                if (ends.get(i) < ends.get(i - 1)) {
                    throw new IllegalStateException("offset 非单调: " + i);
                }
            }
            int[] arr = new int[ends.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = ends.get(i);
            }
            return new VarLenVector(data.toByteArray(), arr, 0, validity.build(), 0, arr.length - 1, false);
        }
    }
}
