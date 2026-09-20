package cn.chyuan.ai.domain.storekernel.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LSM 存储引擎（工单 0476 BE5 + 0479 BE8，leveldb/rocksdb 思想，纯内存）。
 * 读写路径：memtable → L0 → Ln 从新到旧归并取首个命中（序列号快照一致读）
 * /删除墓碑语义（命中墓碑即不存在）/memtable 满触发 flush/L0 层满触发 leveled compaction
 * /批量写/统计快照。store-kernel.enabled 默认关。
 */
public class LsmEngine {

    /** 统计快照 */
    public record Stats(long sequence, int memtableEntries, int l0Segments,
            int[] levelSegments, long bloomChecks, long bloomPruned,
            long bytesRead, long bytesWritten, double writeAmplification) {
    }

    private final MemTable memtable = new MemTable(4);
    private final WriteAheadLog wal = new WriteAheadLog(8);
    private final List<List<SstSegment>> levels = new ArrayList<>();
    private final LeveledCompaction compaction = new LeveledCompaction();
    private final java.util.Map<Long, BloomFilter> segmentBlooms = new java.util.concurrent.ConcurrentHashMap<>();
    private final int maxLevel;
    private final int l0CompactionTrigger;
    private long sequence;
    private long bloomChecks;
    private long bloomPruned;

    public LsmEngine(int maxLevel, int l0CompactionTrigger) {
        if (maxLevel < 1) {
            throw new IllegalArgumentException("层数须 ≥ 1");
        }
        if (l0CompactionTrigger < 1) {
            throw new IllegalArgumentException("L0 触发阈值须 ≥ 1");
        }
        this.maxLevel = maxLevel;
        this.l0CompactionTrigger = l0CompactionTrigger;
        for (int i = 0; i <= maxLevel; i++) {
            levels.add(new ArrayList<>());
        }
    }

    /** 写入（分配单调序列号 + WAL 追加） */
    public synchronized long put(String key, String value) {
        long seq = ++sequence;
        wal.append(new WriteAheadLog.Entry(seq, false, key, value));
        memtable.put(key, seq, value);
        maybeFlush();
        return seq;
    }

    /** 删除（墓碑） */
    public synchronized long delete(String key) {
        long seq = ++sequence;
        wal.append(new WriteAheadLog.Entry(seq, true, key, null));
        memtable.put(key, seq, null);
        maybeFlush();
        return seq;
    }

    /** 批量写（同批序列号连续单调，任一失败不部分生效由调用方前置校验） */
    public synchronized List<Long> batchPut(List<String[]> pairs) {
        List<Long> sequences = new ArrayList<>(pairs.size());
        for (String[] pair : pairs) {
            sequences.add(put(pair[0], pair[1]));
        }
        return sequences;
    }

    /** 读最新 */
    public synchronized String get(String key) {
        return getAt(key, sequence);
    }

    /** 快照一致读：仅可见序列号 ≤ snapshotSequence 的版本 */
    public synchronized String getAt(String key, long snapshotSequence) {
        MemTable.MemEntry memEntry = memtable.getAt(key, snapshotSequence);
        if (memEntry != null) {
            return memEntry.tombstone() ? null : memEntry.value();
        }
        for (int level = 0; level <= maxLevel; level++) {
            for (SstSegment segment : levels.get(level)) {
                bloomChecks++;
                BloomFilter bloom = segmentBlooms.get(segment.segmentId());
                if (bloom != null && !bloom.mightContain(key)) {
                    bloomPruned++;
                    continue;
                }
                if (!segment.mayContainKey(key)) {
                    continue;
                }
                SstSegment.Row row = segment.find(key);
                if (row != null && row.sequence() <= snapshotSequence) {
                    return row.tombstone() ? null : row.value();
                }
            }
        }
        return null;
    }

    /** 当前序列号（快照游标） */
    public synchronized long currentSequence() {
        return sequence;
    }

    /** 前缀扫描（跳过墓碑） */
    public synchronized List<SstSegment.Row> scanPrefix(String prefix) {
        List<SstSegment> segmentsNewestFirst = newestFirstSegments();
        RangeIterator iterator = RangeIterator.merge(
                new ArrayList<>(memtable.tailMap(prefix).entrySet()),
                segmentsNewestFirst, sequence, prefix, true);
        List<SstSegment.Row> result = new ArrayList<>();
        while (iterator.hasNext()) {
            RangeIterator.Visible visible = iterator.next();
            result.add(new SstSegment.Row(visible.key(), visible.sequence(), visible.value()));
        }
        return result;
    }

    /** 跨段有序迭代器（可 seek/反转） */
    public synchronized RangeIterator iterator(long snapshotSequence) {
        return RangeIterator.merge(new ArrayList<>(), newestFirstSegments(), snapshotSequence, null, true);
    }

    /** 崩溃恢复：重放 WAL 至 memtable（序列号续写） */
    public synchronized int recover() {
        List<WriteAheadLog.Entry> replayed = wal.replayFrom(sequenceOfLastApplied());
        int applied = 0;
        for (WriteAheadLog.Entry entry : replayed) {
            memtable.put(entry.key(), entry.sequence(), entry.delete() ? null : entry.value());
            if (entry.sequence() > sequence) {
                sequence = entry.sequence();
            }
            applied++;
        }
        return applied;
    }

    /** WAL 重放游标（演示崩溃恢复语义：memtable 已含但 WAL 亦在，重放幂等） */
    private long sequenceOfLastApplied() {
        return 0L;
    }

    /** 统计快照 */
    public synchronized Stats stats() {
        int[] levelCounts = new int[maxLevel + 1];
        for (int i = 0; i <= maxLevel; i++) {
            levelCounts[i] = levels.get(i).size();
        }
        return new Stats(sequence, memtable.entryCount(), levels.get(0).size(), levelCounts,
                bloomChecks, bloomPruned, compaction.bytesReadTotal(), compaction.bytesWrittenTotal(),
                compaction.writeAmplification());
    }

    /** memtable 满 → flush 成 L0 段（带 bloom），L0 满 → 触发 compaction */
    private void maybeFlush() {
        if (!memtable.shouldFlush()) {
            return;
        }
        List<java.util.Map.Entry<String, MemTable.MemEntry>> exported = memtable.drain();
        SstSegment segment = SstSegment.build(nextSegmentId(), 0, exported);
        levels.get(0).add(segment);
        BloomFilter bloom = BloomFilter.create(Math.max(16, segment.rowCount()), 0.01);
        for (SstSegment.Row row : segment.rows()) {
            bloom.add(row.key());
        }
        segmentBlooms.put(segment.segmentId(), bloom);
        while (levels.get(0).size() >= l0CompactionTrigger && maxLevel >= 1) {
            LeveledCompaction.Inputs inputs = compaction.selectLevel0(
                    List.copyOf(levels.get(0)), List.copyOf(levels.get(1)), l0CompactionTrigger);
            boolean bottom = inputs.outputLevel() == maxLevel;
            LeveledCompaction.Result result = compaction.compact(inputs, bottom, maxLevel);
            levels.get(0).clear();
            levels.get(1).removeAll(inputs.inputs());
            for (SstSegment input : inputs.inputs()) {
                segmentBlooms.remove(input.segmentId());
            }
            if (result.output().rowCount() > 0) {
                levels.get(1).add(result.output());
                BloomFilter outputBloom = BloomFilter.create(Math.max(16, result.output().rowCount()), 0.01);
                for (SstSegment.Row row : result.output().rows()) {
                    outputBloom.add(row.key());
                }
                segmentBlooms.put(result.output().segmentId(), outputBloom);
            }
        }
    }

    private long segmentIdCounter;

    private long nextSegmentId() {
        return ++segmentIdCounter;
    }

    /** 新到旧段视图（L0 新段在前，L1..Ln 依层） */
    private List<SstSegment> newestFirstSegments() {
        List<SstSegment> ordered = new ArrayList<>();
        List<SstSegment> l0 = levels.get(0);
        for (int i = l0.size() - 1; i >= 0; i--) {
            ordered.add(l0.get(i));
        }
        for (int level = 1; level <= maxLevel; level++) {
            ordered.addAll(levels.get(level));
        }
        return ordered;
    }

    /** 只读层视图（测试/表同步用） */
    public synchronized List<List<SstSegment>> levelView() {
        List<List<SstSegment>> view = new ArrayList<>();
        for (List<SstSegment> level : levels) {
            view.add(List.copyOf(level));
        }
        return Collections.unmodifiableList(view);
    }
}
