package cn.chyuan.ai.domain.bufkernel.service;

import java.util.List;

/**
 * 内存池端口（工单 1203 FE8，netty 思想）。
 * allocate·retain·release·slice 入口统一编排：引用计数·arena 分配·池化归还·切片派生·
 * 读写指针·线程本地缓存·泄漏检测组合管线/storekernel 段形状只读联动
 * （Row: key/sequence/value 字段名对齐，不 import storekernel）/buf-kernel.enabled 默认关。
 */
public interface BufPort {

    /** 分配（走共享池，FE2/FE3） */
    default int allocate(int size) {
        return allocate(size, null);
    }

    /** 分配（指定线程走线程本地缓存，FE6） */
    int allocate(int size, String thread);

    /** 引用计数保留（FE1） */
    void retain(int bufId);

    /** 释放（走共享分级池，FE1/FE3） */
    default void release(int bufId) {
        release(bufId, null);
    }

    /** 释放（指定线程入线程本地缓存，FE6） */
    void release(int bufId, String thread);

    /** 当前引用数（FE1） */
    int refs(int bufId);

    /** 追加写入（FE5） */
    void write(int bufId, byte[] data);

    /** 从读指针读取（FE5） */
    byte[] read(int bufId, int length);

    /** 可读字节数（FE5） */
    int readable(int bufId);

    /** 共享切片：随父存亡（FE4） */
    int slice(int bufId, int index, int length);

    /** 保留切片：独立引用计数（FE4） */
    int retainedSlice(int bufId, int index, int length);

    /** 泄漏报告（FE7） */
    List<Integer> leaks();

    /** chunk 数（FE2） */
    int chunks();

    /** 已用字节（FE2） */
    int usedBytes();

    /** storekernel 段形状只读联动（Row: key/sequence/value） */
    List<String> segmentShape();

    static BufPort inMemory() {
        return new BufServer();
    }
}
