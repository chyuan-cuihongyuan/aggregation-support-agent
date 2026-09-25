package cn.chyuan.ai.domain.framekernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分组聚合与连接（工单 0872-0873 CY3·CY4，polars 思想）。
 * groupby 键分组与 sum·min·max·count 聚合空值跳过/hash join 等值内连接空键不匹配。
 */
public final class GroupJoin {

    public enum Func { SUM, MIN, MAX, COUNT }

    public record Agg(String column, Func func) {
        public String outputName() {
            return column + "_" + func.name().toLowerCase();
        }
    }

    /** groupby：键列 + 聚合列（命名 col_func）；空输入返回同构空表 */
    public static DataFrame groupBy(DataFrame df, List<String> keys, List<Agg> aggs) {
        keys.forEach(df::requireColumn);
        aggs.forEach(a -> df.requireColumn(a.column()));
        Map<String, List<Integer>> buckets = new LinkedHashMap<>();
        for (int r = 0; r < df.rows(); r++) {
            StringBuilder key = new StringBuilder();
            for (String k : keys) {
                key.append(df.cell(k, r)).append('\u0001');
            }
            buckets.computeIfAbsent(key.toString(), x -> new ArrayList<>()).add(r);
        }
        List<String> outNames = new ArrayList<>(keys);
        aggs.forEach(a -> outNames.add(a.outputName()));
        List<List<Object>> outCols = new ArrayList<>();
        for (int i = 0; i < outNames.size(); i++) {
            outCols.add(new ArrayList<>());
        }
        for (List<Integer> bucket : buckets.values()) {
            for (int i = 0; i < keys.size(); i++) {
                outCols.get(i).add(df.cell(keys.get(i), bucket.get(0)));
            }
            for (int i = 0; i < aggs.size(); i++) {
                outCols.get(keys.size() + i).add(aggregate(df, aggs.get(i), bucket));
            }
        }
        return DataFrame.builder().buildFromColumns(outNames, outCols);
    }

    private static Object aggregate(DataFrame df, Agg agg, List<Integer> bucket) {
        if (agg.func() == Func.COUNT) {
            long n = 0;
            for (int r : bucket) {
                if (df.cell(agg.column(), r) != null) {
                    n++;
                }
            }
            return n;
        }
        Long accL = null;
        Double accD = null;
        String accS = null;
        for (int r : bucket) {
            Object v = df.cell(agg.column(), r);
            if (v == null) {
                continue;
            }
            if (v instanceof Long n) {
                accL = accL == null ? n : switch (agg.func()) {
                    case SUM -> accL + n;
                    case MIN -> Math.min(accL, n);
                    default -> Math.max(accL, n);
                };
            } else if (v instanceof Double d) {
                accD = accD == null ? d : switch (agg.func()) {
                    case SUM -> accD + d;
                    case MIN -> Math.min(accD, d);
                    default -> Math.max(accD, d);
                };
            } else if (v instanceof String s) {
                accS = accS == null ? s : switch (agg.func()) {
                    case MIN -> accS.compareTo(s) < 0 ? accS : s;
                    default -> accS.compareTo(s) > 0 ? accS : s;
                };
            } else {
                throw new IllegalArgumentException("聚合不支持类型: " + v.getClass());
            }
        }
        return accL != null ? accL : accD != null ? accD : accS;
    }

    /** hash join：等值内连接；null 键不匹配；键列取左值，其余列左右拼接 */
    public static DataFrame hashJoin(DataFrame left, DataFrame right, String key) {
        left.requireColumn(key);
        right.requireColumn(key);
        Map<String, List<Integer>> index = new LinkedHashMap<>();
        for (int r = 0; r < right.rows(); r++) {
            Object k = right.cell(key, r);
            if (k != null) {
                index.computeIfAbsent(String.valueOf(k), x -> new ArrayList<>()).add(r);
            }
        }
        List<String> names = new ArrayList<>();
        List<List<Object>> cols = new ArrayList<>();
        for (String n : left.columnNames()) {
            names.add(n);
            cols.add(new ArrayList<>());
        }
        for (String n : right.columnNames()) {
            if (n.equals(key)) {
                continue;
            }
            names.add(n);
            cols.add(new ArrayList<>());
        }
        for (int lr = 0; lr < left.rows(); lr++) {
            Object k = left.cell(key, lr);
            if (k == null || !index.containsKey(String.valueOf(k))) {
                continue;
            }
            for (int rr : index.get(String.valueOf(k))) {
                int c = 0;
                for (String n : left.columnNames()) {
                    cols.get(c++).add(left.cell(n, lr));
                }
                for (String n : right.columnNames()) {
                    if (n.equals(key)) {
                        continue;
                    }
                    cols.get(c++).add(right.cell(n, rr));
                }
            }
        }
        return DataFrame.builder().buildFromColumns(names, cols);
    }

    /** 稳定 top-k（降序取最大 k 行，polars top-k 语义） */
    public static DataFrame topKBySort(DataFrame df, String column, int k) {
        return df.topK(column, k, true);
    }
}
