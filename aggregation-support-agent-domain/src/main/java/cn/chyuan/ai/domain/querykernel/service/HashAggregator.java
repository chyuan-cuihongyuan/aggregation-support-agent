package cn.chyuan.ai.domain.querykernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 哈希聚合（工单 0537 BL5，duckdb 聚合思想）。
 * 多列分组键哈希桶/sum-avg-min-max-count 五聚合/having 对聚合结果过滤/
 * 无分组全局聚合恒一行/空输入分组语义（有组键空输出，无组键一行）。
 */
public final class HashAggregator {

    /** 单聚合结果行（组键值 + 聚合值，按 schema 顺序） */
    public ColumnBatch aggregate(ColumnBatch input, List<Integer> groupCols, List<LogicalPlan.AggSpec> aggs,
            ExprEval.Expr having, ExprEval eval) {
        List<String> schema = schemaOf(input, groupCols, aggs);
        Map<List<Object>, List<List<Object>>> buckets = new LinkedHashMap<>();
        for (int r = 0; r < input.rowCount(); r++) {
            List<Object> row = input.row(r);
            List<Object> key = new ArrayList<>(groupCols.size());
            for (Integer col : groupCols) {
                key.add(row.get(col));
            }
            List<List<Object>> acc = buckets.computeIfAbsent(key, k -> {
                List<List<Object>> init = new ArrayList<>(aggs.size());
                for (int i = 0; i < aggs.size(); i++) {
                    init.add(new ArrayList<>());
                }
                return init;
            });
            for (int i = 0; i < aggs.size(); i++) {
                acc.get(i).add(row.get(aggs.get(i).colIdx()));
            }
        }
        List<List<Object>> outCols = new ArrayList<>(schema.size());
        for (int i = 0; i < schema.size(); i++) {
            outCols.add(new ArrayList<>());
        }
        for (Map.Entry<List<Object>, List<List<Object>>> bucket : buckets.entrySet()) {
            List<Object> outRow = new ArrayList<>(schema.size());
            List<Object> key = bucket.getKey();
            outRow.addAll(key);
            for (int i = 0; i < aggs.size(); i++) {
                outRow.add(reduce(aggs.get(i), bucket.getValue().get(i)));
            }
            if (having == null || Boolean.TRUE.equals(eval.eval(having, outRow))) {
                for (int c = 0; c < outRow.size(); c++) {
                    outCols.get(c).add(outRow.get(c));
                }
            }
        }
        // 无分组且无行：仍输出一行（SQL 全局聚合语义）
        if (groupCols.isEmpty() && buckets.isEmpty()) {
            List<Object> outRow = new ArrayList<>();
            for (LogicalPlan.AggSpec ignored : aggs) {
                outRow.add(null);
            }
            // count 空输入为 0
            for (int i = 0; i < aggs.size(); i++) {
                if ("COUNT".equals(aggs.get(i).func())) {
                    outRow.set(i, 0L);
                }
            }
            if (having == null || Boolean.TRUE.equals(eval.eval(having, outRow))) {
                for (int c = 0; c < outRow.size(); c++) {
                    outCols.get(c).add(outRow.get(c));
                }
            }
        }
        return new ColumnBatch(schema, outCols);
    }

    private Object reduce(LogicalPlan.AggSpec spec, List<Object> values) {
        List<Object> nonNull = values.stream().filter(java.util.Objects::nonNull).toList();
        return switch (spec.func()) {
            case "COUNT" -> (long) nonNull.size();
            case "SUM" -> nonNull.isEmpty() ? null : nonNull.stream().mapToDouble(v -> ((Number) v).doubleValue())
                    .sum();
            case "AVG" -> nonNull.isEmpty() ? null
                    : nonNull.stream().mapToDouble(v -> ((Number) v).doubleValue()).average().orElse(0.0d);
            case "MIN" -> nonNull.stream().min(this::compareValues).orElse(null);
            case "MAX" -> nonNull.stream().max(this::compareValues).orElse(null);
            default -> throw new IllegalArgumentException("非法聚合函数: " + spec.func());
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        return ((Comparable) a).compareTo(b);
    }

    private List<String> schemaOf(ColumnBatch input, List<Integer> groupCols, List<LogicalPlan.AggSpec> aggs) {
        List<String> schema = new ArrayList<>();
        for (Integer col : groupCols) {
            schema.add(input.names().get(col));
        }
        for (LogicalPlan.AggSpec agg : aggs) {
            schema.add(agg.alias());
        }
        return schema;
    }
}
