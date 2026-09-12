package cn.chyuan.ai.domain.crew.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 共享黑板（工单 0217 AC5，AutoGPT 共享内存/黑板模式）—
 * 多角色读写的键值事实板：每键递增版本号；带期望版本的写入（putIfVersion）
 * 版本不符即拒绝（防旧写覆盖新事实，乐观并发）；put 直接写（last-writer-wins）。
 * snapshot 输出不可变副本。
 *
 * @author chyuan
 */
public class Blackboard {

    private static final class Entry {
        final Object value;
        final long version;

        Entry(Object value, long version) {
            this.value = value;
            this.version = version;
        }
    }

    private final ConcurrentHashMap<String, Entry> facts = new ConcurrentHashMap<>();
    private final AtomicLong totalWrites = new AtomicLong();

    /** 直接写入（last-writer-wins），返回新版本号（从 1 起） */
    public long put(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("黑板键不能为空");
        }
        totalWrites.incrementAndGet();
        long version = facts.compute(key, (k, prev) ->
                new Entry(value, prev == null ? 1 : prev.version + 1)).version;
        return version;
    }

    /** 乐观写入：期望版本不符（键已被并发更新）返回 -1 拒绝；成功返回新版本号 */
    public long putIfVersion(String key, Object value, long expectedVersion) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("黑板键不能为空");
        }
        totalWrites.incrementAndGet();
        long[] result = new long[1];
        facts.compute(key, (k, prev) -> {
            long current = prev == null ? 0 : prev.version;
            if (current != expectedVersion) {
                result[0] = -1;
                return prev;
            }
            result[0] = current + 1;
            return new Entry(value, current + 1);
        });
        return result[0];
    }

    /** 读值（无则 null） */
    public Object get(String key) {
        Entry entry = facts.get(key);
        return entry == null ? null : entry.value;
    }

    /** 当前版本号（无该键返回 0） */
    public long versionOf(String key) {
        Entry entry = facts.get(key);
        return entry == null ? 0 : entry.version;
    }

    /** 键集合（保序快照） */
    public java.util.Set<String> keys() {
        return java.util.Set.copyOf(facts.keySet());
    }

    /** 不可变快照（键 → 值，键序稳定）——用 unmodifiableMap(LinkedHashMap) 保序（Map.copyOf 不保证迭代序） */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        facts.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey(), e.getValue().value));
        return java.util.Collections.unmodifiableMap(out);
    }

    /** 累计写入次数（观测） */
    public long totalWrites() {
        return totalWrites.get();
    }
}
