package cn.chyuan.ai.domain.leasekernel.service;

import java.util.List;

/**
 * 租约监听端口（工单 1187 FC8，etcd 思想）。
 * grant·put·keepalive·watch 入口统一编排：修订全序·租约授予·保活撤销·watch 事件流·压缩组合管线/
 * raftkernel 日志条目形状只读联动（term/index 字段名对齐，不 import raftkernel）/
 * lease-kernel.enabled 默认关（开启才改变行为）。
 */
public interface LeasePort {

    /** 授予租约：唯一 leaseId，到期=当前时钟+TTL（FC2） */
    long grant(long ttl);

    /** 挂靠 key 到租约；未知租约拒绝（FC2） */
    void attach(long leaseId, String key);

    /** 写入（leaseId=0 表示不挂租约），返回修订（FC1） */
    long put(String key, String value, long leaseId);

    /** 删除写 tombstone，返回修订与存在性（FC1） */
    Revision.DeleteResult delete(String key);

    /** 当前值（FC1） */
    String get(String key);

    /** 历史读：≤ 指定修订的最近版本（FC1/FC7） */
    String getAt(String key, long revision);

    /** 当前最大修订（FC1） */
    long revision();

    /** 保活：刷新到期时刻（FC3） */
    long keepAlive(long leaseId);

    /** 推进虚拟时钟（FC3/FC7 时间语义，不依赖系统睡眠） */
    void advance(long ticks);

    /** 回收到期租约，返回被清除 key（FC3） */
    List<String> reclaim();

    /** 撤销租约，返回被清除 key；重复撤销幂等（FC4） */
    List<String> revoke(long leaseId);

    /** 租约是否已被到期回收（FC3） */
    boolean reclaimed(long leaseId);

    /** 订阅：空前缀全收（FC5/FC6） */
    default long watch(long startRevision) {
        return watch(startRevision, "");
    }

    /** 订阅：指定前缀（FC5/FC6） */
    long watch(long startRevision, String prefix);

    /** 收取事件（FC5/FC6） */
    List<WatchHub.Event> collect(long watchId);

    /** 压缩（FC7） */
    void compact(long revision);

    /** raftkernel 日志条目形状只读联动（Entry: term/index） */
    List<String> raftShape();

    static LeasePort inMemory() {
        return new LeaseServer();
    }
}
