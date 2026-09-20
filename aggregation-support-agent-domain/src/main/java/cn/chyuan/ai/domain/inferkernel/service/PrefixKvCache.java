package cn.chyuan.ai.domain.inferkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前缀 KV 缓存（工单 0510 BI7，llama.cpp KV cache 思想）。
 * 前缀命中统计（最长公共前缀匹配）/复用与失效（命中续写）/容量 LRU 淘汰
 * （最久未用先逐出）。
 */
public class PrefixKvCache {

    /** 缓存统计 */
    public record Stats(int entries, long hits, long misses, long evictions) {
    }

    /** 缓存项：前缀 token 序列 + 伪 KV 载荷（长度） */
    private static final class Entry {
        List<Integer> prefix;
        int kvLength;
        long lastUsedAt;
    }

    private final int capacity;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long hits;
    private long misses;
    private long evictions;
    private long tick;

    public PrefixKvCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量须 > 0");
        }
        this.capacity = capacity;
    }

    /** 查找：返回命中前缀长度（0 = 未命中；部分前缀命中返回该前缀长度） */
    public synchronized int lookup(List<Integer> tokens) {
        tick++;
        Entry best = null;
        int bestLength = 0;
        for (Entry entry : entries.values()) {
            int common = commonPrefixLength(entry.prefix, tokens);
            if (common > bestLength) {
                bestLength = common;
                best = entry;
            }
        }
        if (best != null && bestLength == best.prefix.size()) {
            hits++;
            best.lastUsedAt = tick;
            entries.remove(key(best.prefix));
            entries.put(key(best.prefix), best);
            return bestLength;
        }
        misses++;
        return 0;
    }

    /** 写入：追加/更新前缀缓存，LRU 逐出（写前容量检查，最久未用先逐出） */
    public synchronized void put(List<Integer> prefix, int kvLength) {
        tick++;
        String key = key(prefix);
        Entry existing = entries.remove(key);
        if (existing != null) {
            existing.kvLength = kvLength;
            existing.lastUsedAt = tick;
            entries.put(key, existing);
            return;
        }
        while (entries.size() >= capacity) {
            String lru = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> entry : entries.entrySet()) {
                if (entry.getValue().lastUsedAt < oldest) {
                    oldest = entry.getValue().lastUsedAt;
                    lru = entry.getKey();
                }
            }
            entries.remove(lru);
            evictions++;
        }
        Entry entry = new Entry();
        entry.prefix = List.copyOf(prefix);
        entry.kvLength = kvLength;
        entry.lastUsedAt = tick;
        entries.put(key, entry);
    }

    /** 失效：显式清除（返回是否命中） */
    public synchronized boolean invalidate(List<Integer> prefix) {
        return entries.remove(key(prefix)) != null;
    }

    public synchronized Stats stats() {
        return new Stats(entries.size(), hits, misses, evictions);
    }

    private int commonPrefixLength(List<Integer> a, List<Integer> b) {
        int length = Math.min(a.size(), b.size());
        for (int i = 0; i < length; i++) {
            if (!a.get(i).equals(b.get(i))) {
                return i;
            }
        }
        return length;
    }

    private String key(List<Integer> tokens) {
        return new ArrayList<>(tokens).toString();
    }
}
