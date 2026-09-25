package cn.chyuan.ai.domain.framekernel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DataFrame 与关系算子（工单 0870-0871/0874/0876-0877 CY1·CY2·CY5·CY7，polars 思想）。
 * 命名列组构建行数一致/谓词过滤空值语义/多键排序与 top-k/投影与重命名冲突拒绝。
 */
public final class DataFrame {

    /** 比较条件（CY2） */
    public record Condition(String column, Op op, Object value) {
        public enum Op { EQ, NE, LT, LE, GT, GE }
    }

    /** 排序键（CY5） */
    public record SortKey(String column, boolean ascending) {
    }

    private final Map<String, List<Object>> columns;
    private final int rows;

    private DataFrame(Map<String, List<Object>> columns, int rows) {
        this.columns = columns;
        this.rows = rows;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 行集构建：以首行键序定列序，缺键补 null */
    public static DataFrame fromRows(List<Map<String, Object>> rowsAsMaps) {
        Builder b = new Builder();
        if (rowsAsMaps.isEmpty()) {
            return b.build();
        }
        List<String> names = new ArrayList<>(rowsAsMaps.get(0).keySet());
        for (String name : names) {
            List<Object> col = new ArrayList<>();
            for (Map<String, Object> row : rowsAsMaps) {
                col.add(row.get(name));
            }
            b.appendColumn(name, col);
        }
        return b.build();
    }

    public int rows() {
        return rows;
    }

    public int columns() {
        return columns.size();
    }

    public List<String> columnNames() {
        return new ArrayList<>(columns.keySet());
    }

    public List<Object> column(String name) {
        requireColumn(name);
        return List.copyOf(columns.get(name));
    }

    public Object cell(String name, int row) {
        requireColumn(name);
        return columns.get(name).get(row);
    }

    void requireColumn(String name) {
        if (!columns.containsKey(name)) {
            throw new IllegalArgumentException("未知列: " + name);
        }
    }

    /** 谓词过滤：AND 语义；null 参与比较一律排除 */
    public DataFrame filter(List<Condition> conditions) {
        List<Integer> keep = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            boolean ok = true;
            for (Condition c : conditions) {
                Object v = cell(c.column(), r);
                if (v == null || c.value() == null || !compare(v, c.op(), c.value())) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                keep.add(r);
            }
        }
        return selectRows(keep);
    }

    private boolean compare(Object v, Condition.Op op, Object value) {
        int cmp;
        if (v instanceof Number && value instanceof Number) {
            cmp = Double.compare(((Number) v).doubleValue(), ((Number) value).doubleValue());
        } else if (v instanceof String && value instanceof String) {
            cmp = ((String) v).compareTo((String) value);
        } else if (v instanceof Boolean && value instanceof Boolean) {
            cmp = Boolean.compare((Boolean) v, (Boolean) value);
        } else {
            return false;
        }
        return switch (op) {
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
            case GT -> cmp > 0;
            case GE -> cmp >= 0;
        };
    }

    private DataFrame selectRows(List<Integer> keep) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> e : columns.entrySet()) {
            List<Object> col = new ArrayList<>();
            for (int r : keep) {
                col.add(e.getValue().get(r));
            }
            out.put(e.getKey(), col);
        }
        return new DataFrame(out, keep.size());
    }

    /** 多键稳定排序；null 恒排末尾 */
    public DataFrame sort(List<SortKey> keys) {
        for (SortKey k : keys) {
            requireColumn(k.column());
        }
        List<Integer> order = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            order.add(r);
        }
        Comparator<Integer> cmp = (r1, r2) -> {
            for (SortKey key : keys) {
                Object a = cell(key.column(), r1);
                Object b = cell(key.column(), r2);
                if (a == null || b == null) {
                    if (a == null && b == null) {
                        continue;
                    }
                    return a == null ? 1 : -1;
                }
                int c = natural(a, b);
                if (c != 0) {
                    return key.ascending() ? c : -c;
                }
            }
            return 0;
        };
        order.sort(cmp);
        return selectRows(order);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int natural(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        return ((Comparable) a).compareTo(b);
    }

    /** top-k：按列排序后取前 k 行 */
    public DataFrame topK(String column, int k, boolean descending) {
        return sort(List.of(new SortKey(column, !descending))).rows() <= k
                ? sort(List.of(new SortKey(column, !descending)))
                : sort(List.of(new SortKey(column, !descending))).selectRows(range(k));
    }

    private List<Integer> range(int k) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < k && i < rows; i++) {
            out.add(i);
        }
        return out;
    }

    /** 投影：未知列拒绝 */
    public DataFrame project(List<String> names) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        for (String name : names) {
            requireColumn(name);
            out.put(name, columns.get(name));
        }
        return new DataFrame(out, rows);
    }

    /** 重命名：目标名冲突拒绝 */
    public DataFrame rename(Map<String, String> mapping) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> e : columns.entrySet()) {
            String newName = mapping.getOrDefault(e.getKey(), e.getKey());
            if (out.containsKey(newName)) {
                throw new IllegalArgumentException("重命名冲突: " + newName);
            }
            out.put(newName, e.getValue());
        }
        return new DataFrame(out, rows);
    }

    public static final class Builder {
        private final Map<String, List<Object>> columns = new LinkedHashMap<>();
        private int rows = -1;

        public Builder appendColumn(String name, List<Object> values) {
            if (columns.containsKey(name)) {
                throw new IllegalArgumentException("重复列: " + name);
            }
            if (rows >= 0 && values.size() != rows) {
                throw new IllegalArgumentException("行数不一致: " + name);
            }
            rows = values.size();
            columns.put(name, new ArrayList<>(values));
            return this;
        }

        /** 按列名+列数据直构（groupby/join 输出用），行数一致性校验 */
        public DataFrame buildFromColumns(List<String> names, List<List<Object>> cols) {
            int n = cols.isEmpty() ? 0 : cols.get(0).size();
            for (List<Object> col : cols) {
                if (col.size() != n) {
                    throw new IllegalArgumentException("行数不一致");
                }
            }
            Map<String, List<Object>> out = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                out.put(names.get(i), new ArrayList<>(cols.get(i)));
            }
            return new DataFrame(out, n);
        }

        public DataFrame build() {
            return new DataFrame(new LinkedHashMap<>(columns), Math.max(rows, 0));
        }
    }
}
