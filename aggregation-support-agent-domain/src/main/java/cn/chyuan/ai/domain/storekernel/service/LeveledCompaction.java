package cn.chyuan.ai.domain.storekernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * leveled compaction（工单 0474 BE3，leveldb/rocksdb 分层压缩思想）。
 * 层满触发选择（L0 按段数/Ln 按层容量）/多段归并去重保留新版本（序列号大者胜）
 * /墓碑清除（压入最底层时）/写放大计数（读入字节+写出字节）。
 */
public class LeveledCompaction {

    /** compaction 输入选择 */
    public record Inputs(int outputLevel, List<SstSegment> inputs) {
    }

    /** compaction 结果 */
    public record Result(SstSegment output, long bytesRead, long bytesWritten, int tombstonesCleared) {
    }

    private long bytesReadTotal;
    private long bytesWrittenTotal;
    private long nextSegmentId;

    /** L0 层满触发：L0 段数 ≥ 阈值选择全部 L0 + L1 重叠段 */
    public Inputs selectLevel0(List<SstSegment> level0, List<SstSegment> level1, int l0Trigger) {
        if (level0.size() < l0Trigger) {
            throw new IllegalStateException("L0 未达触发阈值 " + l0Trigger + "（当前 " + level0.size() + "）");
        }
        String min = level0.stream().map(SstSegment::minKey).min(String::compareTo).orElseThrow();
        String max = level0.stream().map(SstSegment::maxKey).max(String::compareTo).orElseThrow();
        List<SstSegment> inputs = new ArrayList<>(level0);
        for (SstSegment segment : level1) {
            if (segment.maxKey().compareTo(min) >= 0 && segment.minKey().compareTo(max) <= 0) {
                inputs.add(segment);
            }
        }
        return new Inputs(1, inputs);
    }

    /**
     * 归并执行：键去重保留最大序列号（新版本胜）；
     * 压入最底层（bottomLevel=true）清除墓碑，否则墓碑保留以遮蔽下层旧版本。
     */
    public Result compact(Inputs inputs, boolean bottomLevel, int maxLevel) {
        Map<String, SstSegment.Row> newest = new LinkedHashMap<>();
        long read = 0;
        for (SstSegment segment : inputs.inputs()) {
            read += segment.byteSize();
            for (SstSegment.Row row : segment.rows()) {
                SstSegment.Row existing = newest.get(row.key());
                if (existing == null || row.sequence() > existing.sequence()) {
                    newest.put(row.key(), row);
                }
            }
        }
        int cleared = 0;
        List<SstSegment.Row> survivors = new ArrayList<>(newest.size());
        for (SstSegment.Row row : newest.values()) {
            if (bottomLevel && row.tombstone()) {
                cleared++;
                continue;
            }
            survivors.add(row);
        }
        survivors.sort(java.util.Comparator.comparing(SstSegment.Row::key));
        SstSegment output = SstSegment.fromRows(++nextSegmentId, Math.min(inputs.outputLevel(), maxLevel), survivors);
        long written = output.byteSize();
        bytesReadTotal += read;
        bytesWrittenTotal += written;
        return new Result(output, read, written, cleared);
    }

    /** 写放大 = 累计写出 / 累计读入 */
    public double writeAmplification() {
        if (bytesReadTotal == 0) {
            return 0.0;
        }
        return (double) bytesWrittenTotal / bytesReadTotal;
    }

    public long bytesReadTotal() {
        return bytesReadTotal;
    }

    public long bytesWrittenTotal() {
        return bytesWrittenTotal;
    }

    void seedSegmentId(long id) {
        if (id >= nextSegmentId) {
            nextSegmentId = id + 1;
        }
    }
}
