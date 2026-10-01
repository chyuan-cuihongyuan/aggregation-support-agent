package cn.chyuan.ai.domain.leasekernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 租约生命周期（工单 1181 FC2 / 1182 FC3 / 1183 FC4，etcd 思想）。
 * grant 授予唯一 leaseId 与 TTL；keepalive 按当前时钟+TTL 刷新到期时刻；
 * 到期回收连带清除挂靠 key 并留痕；revoke 立即回收且幂等；撤销后 keepalive 拒绝。
 */
public final class Leases {

    private final LongSupplier clock;
    private final Map<Long, Long> ttls = new LinkedHashMap<>();
    private final Map<Long, Long> expiries = new LinkedHashMap<>();
    private final Map<Long, Set<String>> attached = new HashMap<>();
    private final Set<Long> revoked = new HashSet<>();
    private final Set<Long> reclaimed = new LinkedHashSet<>();
    private long seq;

    public Leases(LongSupplier clock) {
        this.clock = clock;
    }

    /** 授予租约：唯一 leaseId，到期=当前时钟+TTL */
    public long grant(long ttl) {
        if (ttl <= 0) {
            throw new IllegalArgumentException("TTL 须为正: " + ttl);
        }
        long id = ++seq;
        ttls.put(id, ttl);
        expiries.put(id, clock.getAsLong() + ttl);
        attached.put(id, new HashSet<>());
        return id;
    }

    /** 挂靠 key；未知租约拒绝 */
    public void attach(long leaseId, String key) {
        Set<String> keys = require(leaseId);
        keys.add(key);
    }

    /** 到期时刻 */
    public long expiry(long leaseId) {
        Long expiry = expiries.get(leaseId);
        if (expiry == null) {
            throw new IllegalArgumentException("未知租约: " + leaseId);
        }
        return expiry;
    }

    /** 挂靠 key（字典序） */
    public List<String> keys(long leaseId) {
        Set<String> keys = attached.get(leaseId);
        if (keys == null) {
            throw new IllegalArgumentException("未知租约: " + leaseId);
        }
        return keys.stream().sorted().toList();
    }

    /** 保活：按当前时钟+TTL 刷新到期；未知/已撤销/已回收租约拒绝 */
    public long keepAlive(long leaseId) {
        Long ttl = ttls.get(leaseId);
        if (ttl == null) {
            throw new IllegalArgumentException("未知租约: " + leaseId);
        }
        long expiry = clock.getAsLong() + ttl;
        expiries.put(leaseId, expiry);
        return expiry;
    }

    /** 回收到期租约：返回被清除的挂靠 key（按租约授予序、组内 key 字典序），回收租约留痕 */
    public List<String> reclaimExpired() {
        long now = clock.getAsLong();
        List<String> cleared = new ArrayList<>();
        for (Long id : List.copyOf(expiries.keySet())) {
            if (expiries.get(id) <= now) {
                attached.getOrDefault(id, Set.of()).stream().sorted().forEach(cleared::add);
                drop(id);
                reclaimed.add(id);
            }
        }
        return cleared;
    }

    /** 租约是否已被到期回收 */
    public boolean reclaimed(long leaseId) {
        return reclaimed.contains(leaseId);
    }

    /** 撤销：立即回收挂靠 key；重复撤销幂等（空列表）；未知租约拒绝 */
    public List<String> revoke(long leaseId) {
        if (!attached.containsKey(leaseId) && !ttls.containsKey(leaseId) && !revoked.contains(leaseId)) {
            throw new IllegalArgumentException("未知租约: " + leaseId);
        }
        Set<String> keys = attached.remove(leaseId);
        drop(leaseId);
        revoked.add(leaseId);
        return keys == null ? List.of() : keys.stream().sorted().toList();
    }

    private void drop(long leaseId) {
        ttls.remove(leaseId);
        expiries.remove(leaseId);
        attached.remove(leaseId);
    }

    private Set<String> require(long leaseId) {
        Set<String> keys = attached.get(leaseId);
        if (keys == null) {
            throw new IllegalArgumentException("未知租约: " + leaseId);
        }
        return keys;
    }
}
