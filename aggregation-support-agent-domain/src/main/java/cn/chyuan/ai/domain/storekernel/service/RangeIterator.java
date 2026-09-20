package cn.chyuan.ai.domain.storekernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 区间迭代器（工单 0477 BE6，leveldb iterator 思想）。
 * 跨段有序归并迭代（新段优先遮蔽旧段，同键取序列号最大且 ≤ 快照）
 * /seek 定位/方向反转/前缀枚举/墓碑跳过。
 */
public final class RangeIterator {

    /** 归并后的可见行 */
    public record Visible(String key, long sequence, String value) {
        public boolean tombstone() {
            return value == null;
        }
    }

    private final List<Visible> visibleRows;
    private final String prefix;
    private int position;
    private boolean forward = true;

    private RangeIterator(List<Visible> visibleRows, String prefix, boolean descending) {
        this.visibleRows = visibleRows;
        this.prefix = prefix;
        this.forward = !descending;
        this.position = descending ? visibleRows.size() - 1 : 0;
    }

    /**
     * 归并构建：memtable 视图 + 各段（新到旧）行，键去重：仅保留该键首个（即最新）
     * 且序列号 ≤ snapshotSequence 的可见版本；墓碑保留为可见行（由 tombstone 判定），
     * 扫描语义默认跳过墓碑（构造参数 skipTombstones）。
     */
    public static RangeIterator merge(List<java.util.Map.Entry<String, MemTable.MemEntry>> memtableView,
            List<SstSegment> segmentsNewestFirst, long snapshotSequence,
            String prefix, boolean skipTombstones) {
        java.util.Map<String, Visible> merged = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, MemTable.MemEntry> entry : memtableView) {
            if (entry.getValue().sequence() <= snapshotSequence) {
                merged.put(entry.getKey(), new Visible(entry.getKey(), entry.getValue().sequence(), entry.getValue().value()));
            }
        }
        for (SstSegment segment : segmentsNewestFirst) {
            for (SstSegment.Row row : segment.rows()) {
                if (row.sequence() > snapshotSequence || merged.containsKey(row.key())) {
                    continue;
                }
                merged.put(row.key(), new Visible(row.key(), row.sequence(), row.value()));
            }
        }
        List<Visible> rows = new ArrayList<>(merged.values());
        rows.sort(java.util.Comparator.comparing(Visible::key));
        if (skipTombstones) {
            rows.removeIf(Visible::tombstone);
        }
        if (prefix != null && !prefix.isEmpty()) {
            rows.removeIf(row -> !row.key().startsWith(prefix));
        }
        return new RangeIterator(List.copyOf(rows), prefix, false);
    }

    /** seek：定位到第一个 ≥ target 的键（正向）；反向为最后一个 ≤ target */
    public void seek(String target) {
        if (forward) {
            position = 0;
            while (position < visibleRows.size() && visibleRows.get(position).key().compareTo(target) < 0) {
                position++;
            }
        } else {
            position = visibleRows.size() - 1;
            while (position >= 0 && visibleRows.get(position).key().compareTo(target) > 0) {
                position--;
            }
        }
    }

    /** 反转迭代方向（从当前头部重新开始） */
    public void reverse() {
        forward = !forward;
        position = forward ? 0 : visibleRows.size() - 1;
    }

    public boolean hasNext() {
        return forward ? position < visibleRows.size() : position >= 0;
    }

    public Visible next() {
        if (!hasNext()) {
            throw new NoSuchElementException("迭代器已耗尽");
        }
        return visibleRows.get(forward ? position++ : position--);
    }

    public int remaining() {
        return forward ? visibleRows.size() - position : position + 1;
    }

    public String prefix() {
        return prefix;
    }
}
