package cn.chyuan.ai.domain.storekernel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SST 有序不可变段（工单 0473 BE2，leveldb sstable 思想）。
 * 键有序只读/键最小最大元数据/层号归属/段行数与字节数统计/校验和。
 */
public final class SstSegment {

    /** 段内行：键 + 序列号 + 值（null 为墓碑） */
    public record Row(String key, long sequence, String value) {
        public boolean tombstone() {
            return value == null;
        }
    }

    private final long segmentId;
    private final int level;
    private final List<Row> rows;
    private final String minKey;
    private final String maxKey;
    private final long byteSize;
    private final String checksum;

    private SstSegment(long segmentId, int level, List<Row> rows) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("段至少一行");
        }
        this.segmentId = segmentId;
        this.level = level;
        this.rows = List.copyOf(rows);
        this.minKey = rows.get(0).key();
        this.maxKey = rows.get(rows.size() - 1).key();
        long bytes = 0;
        for (Row row : rows) {
            bytes += row.key().length() * 2L + (row.value() == null ? 0 : row.value().length() * 2L) + 8;
        }
        this.byteSize = bytes;
        this.checksum = checksum(rows);
    }

    /** 从 memtable 导出构建（入参须键升序） */
    public static SstSegment build(long segmentId, int level, List<Map.Entry<String, MemTable.MemEntry>> exported) {
        List<Row> rows = new ArrayList<>(exported.size());
        for (Map.Entry<String, MemTable.MemEntry> entry : exported) {
            rows.add(new Row(entry.getKey(), entry.getValue().sequence(), entry.getValue().value()));
        }
        return new SstSegment(segmentId, level, rows);
    }

    /** 归并构建（compaction 输出，入参须键升序去重后） */
    public static SstSegment fromRows(long segmentId, int level, List<Row> rows) {
        return new SstSegment(segmentId, level, rows);
    }

    /** 键区间是否可能包含（含 min/max 边界，配合 bloom 裁剪） */
    public boolean mayContainKey(String key) {
        return key.compareTo(minKey) >= 0 && key.compareTo(maxKey) <= 0;
    }

    /** 二分定位行（键有序），未命中返回 null */
    public Row find(String key) {
        int low = 0;
        int high = rows.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int order = rows.get(mid).key().compareTo(key);
            if (order == 0) {
                return rows.get(mid);
            }
            if (order < 0) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return null;
    }

    /** ≥ fromKey 的行切片（键升序，迭代器/扫描用） */
    public List<Row> tailFrom(String fromKey) {
        int index = firstIndexOf(fromKey);
        return index >= rows.size() ? List.of() : rows.subList(index, rows.size());
    }

    private int firstIndexOf(String fromKey) {
        int low = 0;
        int high = rows.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (rows.get(mid).key().compareTo(fromKey) < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    public long segmentId() {
        return segmentId;
    }

    public int level() {
        return level;
    }

    public List<Row> rows() {
        return rows;
    }

    public String minKey() {
        return minKey;
    }

    public String maxKey() {
        return maxKey;
    }

    public int rowCount() {
        return rows.size();
    }

    public long byteSize() {
        return byteSize;
    }

    public String checksum() {
        return checksum;
    }

    /** 确定性校验和：键序内容 SHA-256 前 16 字节 hex */
    private static String checksum(List<Row> rows) {
        StringBuilder canonical = new StringBuilder();
        for (Row row : rows) {
            canonical.append(row.key()).append('#').append(row.sequence()).append('#')
                    .append(row.tombstone() ? '~' : row.value()).append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
