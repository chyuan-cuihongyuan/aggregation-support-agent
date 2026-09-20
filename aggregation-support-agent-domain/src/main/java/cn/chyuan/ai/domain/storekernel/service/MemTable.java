package cn.chyuan.ai.domain.storekernel.service;

import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 有序内存表 memtable（工单 0472 BE1，leveldb memtable 思想）。
 * 键有序（字典序跳表）/同键新版本覆盖旧版本（序列号大者胜）/
 * 删除为墓碑（value=null）/容量阈值触发 flush。
 */
public class MemTable {

    /** memtable 条目：序列号 + 值（null 为墓碑） */
    public record MemEntry(long sequence, String value) {
        public boolean tombstone() {
            return value == null;
        }
    }

    private final NavigableMap<String, java.util.List<MemEntry>> versions = new ConcurrentSkipListMap<>();
    private final int flushThreshold;
    private long byteSize;

    public MemTable(int flushThreshold) {
        if (flushThreshold <= 0) {
            throw new IllegalArgumentException("flush 阈值须 > 0: " + flushThreshold);
        }
        this.flushThreshold = flushThreshold;
    }

    /** 写入（value=null 为删除墓碑），同键多版本按序列号有序保留 */
    public synchronized void put(String key, long sequence, String value) {
        versions.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(new MemEntry(sequence, value));
        byteSize += key.length() * 2L + (value == null ? 0 : value.length() * 2L);
    }

    /** 读最新版本 */
    public synchronized MemEntry get(String key) {
        java.util.List<MemEntry> chain = versions.get(key);
        return chain == null || chain.isEmpty() ? null : chain.get(chain.size() - 1);
    }

    /** 快照读：序列号 ≤ snapshot 的最新版本 */
    public synchronized MemEntry getAt(String key, long snapshot) {
        java.util.List<MemEntry> chain = versions.get(key);
        if (chain == null) {
            return null;
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (chain.get(i).sequence() <= snapshot) {
                return chain.get(i);
            }
        }
        return null;
    }

    /** 最新版本视图（键 ≥ fromKey 有序，扫描用） */
    public synchronized NavigableMap<String, MemEntry> tailMap(String fromKey) {
        NavigableMap<String, MemEntry> latest = new ConcurrentSkipListMap<>();
        for (Map.Entry<String, java.util.List<MemEntry>> entry : versions.tailMap(fromKey, true).entrySet()) {
            java.util.List<MemEntry> chain = entry.getValue();
            if (!chain.isEmpty()) {
                latest.put(entry.getKey(), chain.get(chain.size() - 1));
            }
        }
        return latest;
    }

    public synchronized boolean shouldFlush() {
        return versions.size() >= flushThreshold;
    }

    public synchronized int entryCount() {
        return versions.size();
    }

    public synchronized long byteSize() {
        return byteSize;
    }

    /** flush 导出最新版本有序快照并清空（返回键升序条目） */
    public synchronized java.util.List<Map.Entry<String, MemEntry>> drain() {
        java.util.List<Map.Entry<String, MemEntry>> exported = new java.util.ArrayList<>(tailMap("\0").entrySet());
        versions.clear();
        byteSize = 0;
        return exported;
    }
}
