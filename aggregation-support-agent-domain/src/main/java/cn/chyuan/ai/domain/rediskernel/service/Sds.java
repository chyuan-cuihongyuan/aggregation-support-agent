package cn.chyuan.ai.domain.rediskernel.service;

import java.util.Arrays;

/**
 * SDS 动态字符串（工单 0647 BY1，redis 思想）。
 * len/alloc 元数据与已用空间/追加扩容预分配（<1MB 翻倍、≥1MB 多补 1MB）/
 * 惰性释放（clear 保留缓冲待复用，仅置 len=0）/二进制安全/非法参数拒绝。
 */
public final class Sds {

    /** 扩容预分配阈值（redis SDS_MAX_PREALLOC） */
    public static final int MAX_PREALLOC = 1_048_576;

    private byte[] buf;
    private int len;
    private int alloc;

    private Sds(byte[] initial, int capacity) {
        this.buf = Arrays.copyOf(initial, capacity);
        this.len = initial.length;
        this.alloc = capacity;
    }

    /** 包裹字节数组（二进制安全，允许含 0 字节） */
    public static Sds wrap(byte[] src) {
        if (src == null) {
            throw new IllegalArgumentException("SDS 源字节不得为 null");
        }
        return new Sds(src, src.length);
    }

    /** 定容空串 */
    public static Sds empty(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("SDS 容量不得为负");
        }
        return new Sds(new byte[0], capacity);
    }

    public int length() {
        return len;
    }

    public int alloc() {
        return alloc;
    }

    /** 剩余空闲容量（alloc-len，惰性释放后即为可复用空间） */
    public int freeAvailable() {
        return alloc - len;
    }

    /** 取值拷贝（二进制安全） */
    public byte[] bytes() {
        return Arrays.copyOf(buf, len);
    }

    /** 追加：扩容时按 redis 预分配策略（新长 <1MB 翻倍，否则多补 1MB） */
    public void append(byte[] more) {
        if (more == null) {
            throw new IllegalArgumentException("SDS 追加字节不得为 null");
        }
        int newLen = len + more.length;
        if (newLen > alloc) {
            int grown = newLen;
            if (grown < MAX_PREALLOC) {
                grown *= 2;
            } else {
                grown += MAX_PREALLOC;
            }
            buf = Arrays.copyOf(buf, grown);
            alloc = grown;
        }
        System.arraycopy(more, 0, buf, len, more.length);
        len = newLen;
    }

    /** 惰性释放：仅置 len=0，缓冲保留待复用 */
    public void clear() {
        len = 0;
    }
}
