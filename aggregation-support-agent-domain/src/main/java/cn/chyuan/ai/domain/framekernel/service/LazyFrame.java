package cn.chyuan.ai.domain.framekernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 惰性计划（工单 0875 CY6，polars 惰性思想）。
 * 算子树构建/谓词下推折叠（project 与 filter 交换）/计划解释打印/collect 执行。
 */
public final class LazyFrame {

    /** 算子节点 */
    public static final class Node {
        public final String kind;
        public final Object arg;

        Node(String kind, Object arg) {
            this.kind = kind;
            this.arg = arg;
        }
    }

    private final List<Node> plan = new ArrayList<>();

    public static LazyFrame of(DataFrame source) {
        LazyFrame lf = new LazyFrame();
        lf.plan.add(new Node("scan", source));
        return lf;
    }

    public LazyFrame filter(DataFrame.Condition condition) {
        plan.add(new Node("filter", condition));
        return this;
    }

    public LazyFrame groupBy(List<String> keys, List<GroupJoin.Agg> aggs) {
        plan.add(new Node("groupby", List.of(keys, aggs)));
        return this;
    }

    public LazyFrame project(List<String> columns) {
        plan.add(new Node("project", columns));
        return this;
    }

    /** 谓词下推：project 之后的 filter 交换到 project 之前（无聚合时安全） */
    public int pushdown() {
        int swapped = 0;
        for (int i = 0; i < plan.size() - 1; i++) {
            Node a = plan.get(i);
            Node b = plan.get(i + 1);
            if (a.kind.equals("project") && b.kind.equals("filter")) {
                plan.set(i, b);
                plan.set(i + 1, a);
                swapped++;
            }
        }
        return swapped;
    }

    public String explain() {
        StringBuilder sb = new StringBuilder();
        for (Node node : plan) {
            sb.append(node.kind);
            if (node.kind.equals("filter")) {
                DataFrame.Condition c = (DataFrame.Condition) node.arg;
                sb.append('(').append(c.column()).append(' ').append(c.op()).append(' ').append(c.value()).append(')');
            } else if (node.kind.equals("project")) {
                @SuppressWarnings("unchecked")
                List<String> cols = (List<String>) node.arg;
                sb.append(cols);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 执行计划 */
    public DataFrame collect() {
        DataFrame df = null;
        for (Node node : plan) {
            switch (node.kind) {
                case "scan" -> df = (DataFrame) node.arg;
                case "filter" -> df = df.filter(List.of((DataFrame.Condition) node.arg));
                case "groupby" -> {
                    @SuppressWarnings("unchecked")
                    List<Object> args = (List<Object>) node.arg;
                    @SuppressWarnings("unchecked")
                    List<String> keys = (List<String>) args.get(0);
                    @SuppressWarnings("unchecked")
                    List<GroupJoin.Agg> aggs = (List<GroupJoin.Agg>) args.get(1);
                    df = GroupJoin.groupBy(df, keys, aggs);
                }
                case "project" -> {
                    @SuppressWarnings("unchecked")
                    List<String> cols = (List<String>) node.arg;
                    df = df.project(cols);
                }
                default -> throw new IllegalArgumentException("未知算子: " + node.kind);
            }
        }
        return df;
    }
}
