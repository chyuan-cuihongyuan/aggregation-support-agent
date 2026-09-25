package cn.chyuan.ai.domain.mvcckernel.service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MVCC 多版本键值存储（工单 0915-0921 DD1-DD7，etcd MVCC 思想）。
 * 全局 revision 递增与键 mod_revision/多版本读/watch 事件流断点续传/compact 压缩与断裂拒绝/compare-then-put 事务/lease 租约步进到期/前缀范围。
 */
public final class MvccStore {

    /** 版本值：value=null 即墓碑（删除） */
    public record Versioned(long revision, String value) {
        public boolean isTombstone() {
            return value == null;
        }
    }

    public enum EventType { PUT, DELETE }

    /** watch 事件 */
    public record Event(long revision, EventType type, String key, String value) {
    }

    /** 租约 */
    public static final class Lease {
        public final long id;
        public final int ttlSteps;
        public int remaining;
        public final List<String> keys = new ArrayList<>();
        public boolean expired = false;

        Lease(long id, int ttlSteps) {
            this.id = id;
            this.ttlSteps = ttlSteps;
            this.remaining = ttlSteps;
        }
    }

    private long currentRevision = 0;
    private final Map<String, List<Versioned>> history = new TreeMap<>();
    private final List<Event> events = new ArrayList<>();
    private long compactRevision = 0;
    private final Map<Long, Lease> leases = new LinkedHashMap<>();
    private long leaseSeq = 0;

    public long currentRevision() {
        return currentRevision;
    }

    public long compactRevision() {
        return compactRevision;
    }

    /** 写入：revision 递增并记录 PUT 事件；可绑定租约 */
    public long put(String key, String value) {
        return put(key, value, null);
    }

    public long put(String key, String value, Long leaseId) {
        if (leaseId != null && !leases.containsKey(leaseId)) {
            throw new IllegalArgumentException("未知租约: " + leaseId);
        }
        long rev = ++currentRevision;
        history.computeIfAbsent(key, k -> new ArrayList<>()).add(new Versioned(rev, value));
        events.add(new Event(rev, EventType.PUT, key, value));
        if (leaseId != null) {
            leases.get(leaseId).keys.add(key);
        }
        return rev;
    }

    /** 删除：墓碑版本 */
    public boolean delete(String key) {
        if (!history.containsKey(key)) {
            return false;
        }
        long rev = ++currentRevision;
        history.get(key).add(new Versioned(rev, null));
        events.add(new Event(rev, EventType.DELETE, key, null));
        return true;
    }

    /** 读取：最新版本（墓碑或不存在返回 null） */
    public String get(String key) {
        return getAt(key, currentRevision);
    }

    /** 指定 revision 读：取 rev <= atRevision 的最新版本 */
    public String getAt(String key, long atRevision) {
        List<Versioned> versions = history.get(key);
        if (versions == null) {
            return null;
        }
        String value = null;
        for (Versioned v : versions) {
            if (v.revision() <= atRevision && !v.isTombstone()) {
                value = v.value();
            } else if (v.revision() <= atRevision && v.isTombstone()) {
                value = null;
            }
        }
        return value;
    }

    /** 键当前 mod_revision（最后版本号），不存在返回 -1 */
    public long modRevision(String key) {
        List<Versioned> versions = history.get(key);
        return versions == null || versions.isEmpty() ? -1 : versions.get(versions.size() - 1).revision();
    }

    /** watch：从起始 revision（含）回放事件；起始早于压缩点即断裂拒绝 */
    public List<Event> watch(long fromRevision) {
        if (fromRevision <= compactRevision) {
            throw new IllegalStateException("watch 断裂: 起始 revision 已被压缩 " + fromRevision);
        }
        List<Event> out = new ArrayList<>();
        for (Event e : events) {
            if (e.revision() >= fromRevision) {
                out.add(e);
            }
        }
        return out;
    }

    /** compact：清除 < rev 的历史版本与事件（各键保留其最新版本） */
    public int compact(long rev) {
        int removed = 0;
        for (Map.Entry<String, List<Versioned>> e : history.entrySet()) {
            List<Versioned> versions = e.getValue();
            Versioned latest = versions.get(versions.size() - 1);
            List<Versioned> kept = new ArrayList<>();
            for (Versioned v : versions) {
                if (v.revision() < rev) {
                    removed++;
                } else {
                    kept.add(v);
                }
            }
            if (kept.isEmpty()) {
                kept.add(latest);
                removed--;
            }
            e.setValue(kept);
        }
        compactRevision = Math.max(compactRevision, rev);
        int before = events.size();
        events.removeIf(ev -> ev.revision() < rev);
        removed += before - events.size();
        return removed;
    }

    /** 简化事务：全部比较通过才写入（value 相等比较），返回是否应用 */
    public boolean txn(List<String[]> compares, List<String[]> puts) {
        for (String[] compare : compares) {
            String actual = get(compare[0]);
            String expected = compare[1];
            if (!java.util.Objects.equals(actual, expected)) {
                return false;
            }
        }
        for (String[] put : puts) {
            put(put[0], put[1]);
        }
        return true;
    }

    /** 租约授予 */
    public long grantLease(int ttlSteps) {
        if (ttlSteps <= 0) {
            throw new IllegalArgumentException("租约 TTL 须为正");
        }
        Lease lease = new Lease(++leaseSeq, ttlSteps);
        leases.put(lease.id, lease);
        return lease.id;
    }

    /** 步进：租约到期即清除其绑定键（带 revision 递增） */
    public List<String> advance() {
        List<String> removed = new ArrayList<>();
        for (Lease lease : leases.values()) {
            if (!lease.expired) {
                lease.remaining--;
                if (lease.remaining <= 0) {
                    lease.expired = true;
                    for (String key : lease.keys) {
                        delete(key);
                        removed.add(key);
                    }
                }
            }
        }
        return removed;
    }

    /** 前缀范围查询：键 → 最新值 */
    public Map<String, String> getByPrefix(String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : history.keySet()) {
            if (key.startsWith(prefix) && get(key) != null) {
                out.put(key, get(key));
            }
        }
        return out;
    }

    /** 前缀范围删除 */
    public int deleteByPrefix(String prefix) {
        int n = 0;
        for (String key : List.copyOf(history.keySet())) {
            if (key.startsWith(prefix) && delete(key)) {
                n++;
            }
        }
        return n;
    }
}
