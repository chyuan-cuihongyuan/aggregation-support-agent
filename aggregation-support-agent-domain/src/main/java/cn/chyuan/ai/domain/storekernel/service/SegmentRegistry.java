package cn.chyuan.ai.domain.storekernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储段表登记器（工单 0478 BE7）。
 * 段元数据（段 id/层号/键区间/行数字节数/校验和/状态）注册与查询，
 * compaction 淘汰旧段登记幂等（同段 id 重复登记/删除不重复计）。
 * store-kernel.enabled 默认关。持久化面 = 第 30 表 store_segment。
 */
public class SegmentRegistry {

    /** 段状态 */
    public enum Status {
        ACTIVE, COMPACTED
    }

    /** 段登记行（对应 store_segment 表行） */
    public record SegmentRow(long segmentId, int level, String minKey, String maxKey,
            int rowCount, long byteSize, String checksum, Status status) {
    }

    private final Map<Long, SegmentRow> rows = new ConcurrentHashMap<>();

    /** 注册 ACTIVE 段（同段 id 幂等覆盖） */
    public synchronized void register(SstSegment segment) {
        rows.put(segment.segmentId(), new SegmentRow(segment.segmentId(), segment.level(),
                segment.minKey(), segment.maxKey(), segment.rowCount(), segment.byteSize(),
                segment.checksum(), Status.ACTIVE));
    }

    /** 段淘汰（compaction 输入）：置 COMPACTED，返回是否首次淘汰 */
    public synchronized boolean markCompacted(long segmentId) {
        SegmentRow row = rows.get(segmentId);
        if (row == null || row.status() == Status.COMPACTED) {
            return false;
        }
        rows.put(segmentId, new SegmentRow(row.segmentId(), row.level(), row.minKey(), row.maxKey(),
                row.rowCount(), row.byteSize(), row.checksum(), Status.COMPACTED));
        return true;
    }

    /** ACTIVE 段（层号升序、段 id 升序） */
    public synchronized List<SegmentRow> activeSegments() {
        return rows.values().stream()
                .filter(row -> row.status() == Status.ACTIVE)
                .sorted(java.util.Comparator.comparingInt(SegmentRow::level)
                        .thenComparingLong(SegmentRow::segmentId))
                .toList();
    }

    public synchronized int size() {
        return rows.size();
    }
}
