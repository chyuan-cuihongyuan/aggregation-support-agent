package cn.chyuan.ai.domain.storekernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 写前日志 WAL（工单 0472 BE1，leveldb log 思想，内存段实现）。
 * 有序追加（序列号单调）/按段滚动/崩溃恢复重放（从指定序列号回放）。
 * 纯内存模拟段式日志，无真实磁盘 IO。
 */
public class WriteAheadLog {

    /** WAL 记录：序列号 + 操作 + 键 + 值（删除为墓碑标记） */
    public record Entry(long sequence, boolean delete, String key, String value) {
    }

    /** 内存段：段内多条记录 */
    public static final class Segment {
        private final long firstSequence;
        private final List<Entry> entries = new ArrayList<>();

        Segment(long firstSequence) {
            this.firstSequence = firstSequence;
        }

        void append(Entry entry) {
            entries.add(entry);
        }

        public long firstSequence() {
            return firstSequence;
        }

        public int size() {
            return entries.size();
        }

        public List<Entry> entries() {
            return List.copyOf(entries);
        }
    }

    private final int maxEntriesPerSegment;
    private final List<Segment> segments = new ArrayList<>();
    private Segment current;
    private long lastSequence;

    public WriteAheadLog(int maxEntriesPerSegment) {
        if (maxEntriesPerSegment <= 0) {
            throw new IllegalArgumentException("段容量须 > 0: " + maxEntriesPerSegment);
        }
        this.maxEntriesPerSegment = maxEntriesPerSegment;
        this.current = new Segment(0L);
        this.segments.add(current);
    }

    /** 追加写入记录（序列号由引擎单调分配后传入，须大于上一次） */
    public synchronized void append(Entry entry) {
        if (entry.sequence() <= lastSequence) {
            throw new IllegalArgumentException("序列号必须单调递增: " + entry.sequence() + " ≤ " + lastSequence);
        }
        if (current.size() >= maxEntriesPerSegment) {
            current = new Segment(entry.sequence());
            segments.add(current);
        }
        current.append(entry);
        lastSequence = entry.sequence();
    }

    /** 恢复重放：返回 sequence > afterSequence 的全部记录（跨段有序） */
    public synchronized List<Entry> replayFrom(long afterSequence) {
        List<Entry> replayed = new ArrayList<>();
        for (Segment segment : segments) {
            for (Entry entry : segment.entries()) {
                if (entry.sequence() > afterSequence) {
                    replayed.add(entry);
                }
            }
        }
        return replayed;
    }

    public synchronized int segmentCount() {
        return segments.size();
    }

    public synchronized long lastSequence() {
        return lastSequence;
    }
}
