package cn.chyuan.ai.domain.leasekernel.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 租约监听组合实现（工单 1187 FC8，etcd 思想）。
 * 虚拟时钟驱动租约 TTL；put 挂租约经 attach 关联；revoke/到期回收连带清除挂靠 key；
 * watch 从修订起点收事件流；压缩后早于压缩点历史读与订阅拒绝。
 */
public final class LeaseServer implements LeasePort {

    private final Map<String, Long> keyLeases = new HashMap<>();
    private final LongSupplier clock;
    private long tick;
    private final Revision kv = new Revision();
    private final WatchHub watches = new WatchHub(kv);
    private final Leases leases;

    public LeaseServer() {
        this.clock = () -> tick;
        this.leases = new Leases(clock);
    }

    @Override
    public long grant(long ttl) {
        return leases.grant(ttl);
    }

    @Override
    public void attach(long leaseId, String key) {
        leases.attach(leaseId, key);
    }

    @Override
    public long put(String key, String value, long leaseId) {
        if (leaseId != 0) {
            leases.attach(leaseId, key);
            keyLeases.put(key, leaseId);
        }
        return kv.put(key, value);
    }

    @Override
    public Revision.DeleteResult delete(String key) {
        Revision.DeleteResult result = kv.delete(key);
        if (result.existed()) {
            keyLeases.remove(key);
        }
        return result;
    }

    @Override
    public String get(String key) {
        return kv.get(key);
    }

    @Override
    public String getAt(String key, long revision) {
        return kv.getAt(key, revision);
    }

    @Override
    public long revision() {
        return kv.current();
    }

    @Override
    public long keepAlive(long leaseId) {
        return leases.keepAlive(leaseId);
    }

    @Override
    public void advance(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("时钟不得倒退: " + ticks);
        }
        tick += ticks;
    }

    @Override
    public List<String> reclaim() {
        List<String> cleared = leases.reclaimExpired();
        for (String key : cleared) {
            keyLeases.remove(key);
            kv.delete(key);
        }
        return cleared;
    }

    @Override
    public List<String> revoke(long leaseId) {
        List<String> cleared = leases.revoke(leaseId);
        for (String key : cleared) {
            keyLeases.remove(key);
            kv.delete(key);
        }
        return cleared;
    }

    @Override
    public boolean reclaimed(long leaseId) {
        return leases.reclaimed(leaseId);
    }

    @Override
    public long watch(long startRevision, String prefix) {
        return watches.watch(startRevision, prefix);
    }

    @Override
    public List<WatchHub.Event> collect(long watchId) {
        return watches.collect(watchId);
    }

    @Override
    public void compact(long revision) {
        kv.compact(revision);
    }

    @Override
    public List<String> raftShape() {
        return List.of("term", "index");
    }
}
