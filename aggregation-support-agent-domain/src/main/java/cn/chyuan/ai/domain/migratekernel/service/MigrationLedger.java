package cn.chyuan.ai.domain.migratekernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迁移账本（工单 1151 EZ1，prisma 思想）。
 * 名称+内容登记，时间戳 id 递增；重复名称拒绝；登记后账本只读快照；
 * 状态 PENDING/APPLIED/FAILED/BASELINED/SKIPPED/MERGED 由应用器与恢复器推进。
 */
public final class MigrationLedger {

    /** 迁移条目状态 */
    public enum State { PENDING, APPLIED, FAILED, BASELINED, SKIPPED, MERGED }

    /** 迁移条目：递增时间戳 id + 名称 + 抽象迁移体 + 状态 */
    public static final class Entry {
        private final String name;
        private final long timestampId;
        private final String content;
        private State state = State.PENDING;

        Entry(String name, long timestampId, String content) {
            this.name = name;
            this.timestampId = timestampId;
            this.content = content;
        }

        public String name() {
            return name;
        }

        public long timestampId() {
            return timestampId;
        }

        public String content() {
            return content;
        }

        public State state() {
            return state;
        }

        void transition(State target) {
            this.state = target;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private long timestampCounter;

    /** 登记：空名/空迁移体/重复名称拒绝；时间戳 id 递增分配 */
    public Entry record(String name, String content) {
        if (name == null || name.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("迁移名与迁移体不能为空");
        }
        if (entries.containsKey(name)) {
            throw new IllegalArgumentException("重复迁移名称: " + name);
        }
        Entry entry = new Entry(name, ++timestampCounter, content);
        entries.put(name, entry);
        order.add(name);
        return entry;
    }

    /** squash 重组：[fromName..toName] 位置替换为 mergedName（继承最早位置） */
    public void replaceRange(String fromName, String toName, String mergedName) {
        int i = order.indexOf(fromName);
        int j = order.indexOf(toName);
        if (i < 0 || j < 0 || i > j) {
            throw new IllegalArgumentException("非法合并区间: " + fromName + ".." + toName);
        }
        order.remove((Object) mergedName);
        order.add(i, mergedName);
        for (int k = j + 1; k >= i + 1; k--) {
            order.remove(k);
        }
    }

    public Entry get(String name) {
        Entry entry = entries.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("未知迁移: " + name);
        }
        return entry;
    }

    public boolean has(String name) {
        return entries.containsKey(name);
    }

    /** 登记序只读快照（合并条继承最早位置） */
    public List<Entry> snapshot() {
        return order.stream().map(entries::get).toList();
    }

    /** 活跃条目（剔除已合并），squash 后新环境以此起账 */
    public List<Entry> activeEntries() {
        return order.stream().map(entries::get).filter(e -> e.state() != State.MERGED).toList();
    }
}
