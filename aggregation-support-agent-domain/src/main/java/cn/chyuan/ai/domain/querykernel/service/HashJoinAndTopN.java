package cn.chyuan.ai.domain.querykernel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 哈希连接与排序 TopN（工单 0538 BL6，duckdb 连接与堆思想）。
 * 等值 join 构建（build 右表哈希）探测（probe 左表）两阶段/NULL 键不连接/
 * 左外连接未命中补空/多键升降序排序/TopN 小顶堆截断。
 */
public final class HashJoinAndTopN {

    /**
     * 等值连接：输出列 = 左 schema + 右 schema。
     * leftKey/rightKey 为各自侧键列号；NULL 键不参与连接。
     */
    public ColumnBatch join(ColumnBatch left, ColumnBatch right, int leftKey, int rightKey, boolean leftOuter) {
        List<String> schema = new ArrayList<>(left.names());
        schema.addAll(right.names());
        // build：右表按键分桶
        Map<Object, List<Integer>> buckets = new HashMap<>();
        for (int r = 0; r < right.rowCount(); r++) {
            Object key = right.row(r).get(rightKey);
            if (key != null) {
                buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
        }
        List<List<Object>> outCols = new ArrayList<>(schema.size());
        for (int i = 0; i < schema.size(); i++) {
            outCols.add(new ArrayList<>());
        }
        // probe：左表逐行探测
        for (int r = 0; r < left.rowCount(); r++) {
            List<Object> lrow = left.row(r);
            Object key = lrow.get(leftKey);
            List<Integer> matches = key == null ? List.of() : buckets.getOrDefault(key, List.of());
            if (matches.isEmpty() && leftOuter) {
                appendRow(outCols, lrow, null, left.columnCount());
            }
            for (Integer rr : matches) {
                appendRow(outCols, lrow, right.row(rr), left.columnCount());
            }
        }
        return new ColumnBatch(schema, outCols);
    }

    /** 多键排序（asc 升序；null 最小；稳定排序保持输入序） */
    public List<List<Object>> sort(List<List<Object>> rows, List<LogicalPlan.SortKey> keys) {
        List<List<Object>> copy = new ArrayList<>(rows);
        Comparator<List<Object>> cmp = (a, b) -> {
            for (LogicalPlan.SortKey key : keys) {
                int c = compareNullable(a.get(key.colIdx()), b.get(key.colIdx()));
                int r = key.asc() ? c : -c;
                if (r != 0) {
                    return r;
                }
            }
            return 0;
        };
        copy.sort(cmp);
        return copy;
    }

    /** TopN：按单键取最小 n 行（小顶堆截断，null 最小） */
    public List<List<Object>> topN(List<List<Object>> rows, LogicalPlan.SortKey key, int n) {
        if (n < 0) {
            throw new IllegalArgumentException("TopN 数量非负约束");
        }
        Comparator<List<Object>> cmp = (a, b) -> compareNullable(a.get(key.colIdx()), b.get(key.colIdx()));
        PriorityQueue<List<Object>> heap = new PriorityQueue<>(cmp.reversed());
        for (List<Object> row : rows) {
            heap.offer(row);
            if (heap.size() > n) {
                heap.poll();
            }
        }
        List<List<Object>> out = new ArrayList<>(heap);
        out.sort(cmp);
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareNullable(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        return ((Comparable) a).compareTo(b);
    }

    private void appendRow(List<List<Object>> outCols, List<Object> lrow, List<Object> rrow, int leftWidth) {
        for (int c = 0; c < lrow.size(); c++) {
            outCols.get(c).add(lrow.get(c));
        }
        int rightWidth = rrow == null ? 0 : rrow.size();
        for (int c = 0; c < rightWidth; c++) {
            outCols.get(leftWidth + c).add(rrow.get(c));
        }
        if (rrow == null) {
            int total = outCols.size();
            for (int c = leftWidth; c < total; c++) {
                outCols.get(c).add(null);
            }
        }
    }
}
