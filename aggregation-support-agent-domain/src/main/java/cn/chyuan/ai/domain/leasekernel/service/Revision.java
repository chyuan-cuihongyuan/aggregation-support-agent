package cn.chyuan.ai.domain.leasekernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MVCC 修订全序（工单 1180 FC1 / 1186 FC7，etcd 思想）。
 * put/delete 全局单调递增 revision；删除写 tombstone（value=null 条目）不物理删；
 * 历史读取 ≤ 指定修订的最近版本；压缩移除 ≤ 压缩点的条目，早于压缩点历史读拒绝。
 */
public final class Revision {

    /** 单修订条目：value 为 null 即 tombstone */
    public record Entry(long revision, String value) {
        public boolean tombstone() {
            return value == null;
        }
    }

    public record DeleteResult(long revision, boolean existed) {
    }

    /** 变更下沉口：供 WatchHub 记录事件流（同包互引） */
    interface Sink {
        void onPut(long revision, String key, String value);

        void onDelete(long revision, String key);
    }

    private final Map<String, List<Entry>> history = new HashMap<>();
    private Sink sink;
    private long current;
    private long compactRevision;

    public void bind(Sink sink) {
        this.sink = sink;
    }

    /** 当前最大修订 */
    public long current() {
        return current;
    }

    /** 压缩点（0=未压缩） */
    public long compacted() {
        return compactRevision;
    }

    /** 写入并返回修订；修订全局单调 */
    public long put(String key, String value) {
        requireKey(key);
        if (value == null) {
            throw new IllegalArgumentException("value 不得为 null（删除请走 delete）");
        }
        long rev = ++current;
        history.computeIfAbsent(key, k -> new ArrayList<>()).add(new Entry(rev, value));
        if (sink != null) {
            sink.onPut(rev, key, value);
        }
        return rev;
    }

    /** 删除写 tombstone；不存在的 key 不产生修订（existed=false, revision=0） */
    public DeleteResult delete(String key) {
        requireKey(key);
        if (get(key) == null) {
            return new DeleteResult(0, false);
        }
        long rev = ++current;
        history.get(key).add(new Entry(rev, null));
        if (sink != null) {
            sink.onDelete(rev, key);
        }
        return new DeleteResult(rev, true);
    }

    /** 当前值：tombstone 或不存在均返回 null */
    public String get(String key) {
        requireKey(key);
        List<Entry> entries = history.get(key);
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return entries.get(entries.size() - 1).value();
    }

    /** 历史读：≤ 指定修订的最近版本（tombstone 视为 null）；越界拒绝；早于压缩点拒绝 */
    public String getAt(String key, long revision) {
        requireKey(key);
        if (revision <= 0 || revision > current) {
            throw new IllegalArgumentException("修订越界: " + revision);
        }
        if (compactRevision > 0 && revision < compactRevision) {
            throw new IllegalArgumentException("修订早于压缩点: " + revision);
        }
        List<Entry> entries = history.get(key);
        if (entries == null) {
            return null;
        }
        String found = null;
        for (Entry e : entries) {
            if (e.revision() > revision) {
                break;
            }
            found = e.value();
        }
        return found;
    }

    /** 压缩：移除 ≤ 压缩点条目；倒退拒绝；同点幂等；超前拒绝 */
    public void compact(long revision) {
        if (revision <= 0 || revision > current) {
            throw new IllegalArgumentException("压缩点越界: " + revision);
        }
        if (compactRevision > 0 && revision < compactRevision) {
            throw new IllegalArgumentException("压缩点倒退: " + revision);
        }
        if (revision == compactRevision) {
            return;
        }
        for (List<Entry> entries : history.values()) {
            entries.removeIf(e -> e.revision() <= revision);
        }
        compactRevision = revision;
    }

    private void requireKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key 不得为空");
        }
    }
}
