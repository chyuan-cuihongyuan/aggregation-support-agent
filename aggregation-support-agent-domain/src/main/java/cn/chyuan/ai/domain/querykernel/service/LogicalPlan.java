package cn.chyuan.ai.domain.querykernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 逻辑计划 IR（工单 0533 BL1，duckdb 计划树思想）。
 * scan/filter/project/aggregate/join/sort/limit 七算子节点/schema 逐算子传播/
 * 计划文本化 explain（缩进树）。
 */
public final class LogicalPlan {

    /** 聚合函数规格 */
    public record AggSpec(String func, int colIdx, String alias) {
    }

    /** 排序键（asc 升序） */
    public record SortKey(int colIdx, boolean asc) {
    }

    public sealed interface PlanNode permits Scan, Filter, Project, Aggregate, Join, Sort, Limit {
        List<String> schema();

        List<PlanNode> children();

        String label();

        String explain(int indent, StringBuilder sb);
    }

    public record Scan(String table, List<String> columns, long rows) implements PlanNode {
        @Override
        public List<String> schema() {
            return columns;
        }

        @Override
        public List<PlanNode> children() {
            return List.of();
        }

        @Override
        public String label() {
            return "Scan(" + table + " rows=" + rows + ")";
        }

        @Override
        public String explain(int indent, StringBuilder sb) {
            sb.append(" ".repeat(indent)).append(label()).append('\n');
            return sb.toString();
        }
    }

    public record Filter(PlanNode child, ExprEval.Expr predicate) implements PlanNode {
        @Override
        public List<String> schema() {
            return child.schema();
        }

        @Override
        public List<PlanNode> children() {
            return List.of(child());
        }

        @Override
        public String label() {
            return "Filter";
        }

        @Override
        public String explain(int indent, StringBuilder sb) {
            sb.append(" ".repeat(indent)).append(label()).append('\n');
            return child.explain(indent + 2, sb);
        }
    }

    public record Project(PlanNode child, List<ExprEval.Expr> exprs, List<String> names) implements PlanNode {
        public Project {
            if (exprs.size() != names.size()) {
                throw new IllegalArgumentException("投影表达式与列名数不一致");
            }
        }

        @Override
        public List<String> schema() {
            return names;
        }

        @Override
        public List<PlanNode> children() {
            return List.of(child());
        }

        @Override
        public String label() {
            return "Project(" + String.join(",", names) + ")";
        }

        @Override
        public String explain(int indent, StringBuilder sb) {
            sb.append(" ".repeat(indent)).append(label()).append('\n');
            return child.explain(indent + 2, sb);
        }
    }

    public record Aggregate(PlanNode child, List<Integer> groupCols, List<AggSpec> aggs) implements PlanNode {
        @Override
        public List<String> schema() {
            List<String> out = new ArrayList<>();
            List<String> in = child.schema();
            for (Integer col : groupCols) {
                out.add(in.get(col));
            }
            for (AggSpec agg : aggs) {
                out.add(agg.alias());
            }
            return out;
        }

        @Override
        public List<PlanNode> children() {
            return List.of(child());
        }

        @Override
        public String label() {
            return "Aggregate(groups=" + groupCols.size() + ",aggs=" + aggs.size() + ")";
        }

        @Override
        public String explain(int indent, StringBuilder sb) {
            sb.append(" ".repeat(indent)).append(label()).append('\n');
            return child.explain(indent + 2, sb);
        }
    }

    /** 等值连接（leftKey=rightKey；leftOuter 时左表未命中补空） */
    public record Join(PlanNode left, PlanNode right, int leftKey, int rightKey, boolean leftOuter) implements PlanNode {
        @Override
        public List<String> schema() {
            List<String> out = new ArrayList<>(left.schema());
            out.addAll(right.schema());
            return out;
        }

        @Override
        public List<PlanNode> children() {
            return List.of(left(), right());
        }

        @Override
        public String label() {
            return "Join(keys=" + leftKey + "=" + rightKey + (leftOuter ? " left-outer" : "") + ")";
        }

        @Override
        public String explain(int indent, StringBuilder sb) {
            sb.append(" ".repeat(indent)).append(label()).append('\n');
            left.explain(indent + 2, sb);
            return right.explain(indent + 2, sb);
        }
    }

    public record Sort(PlanNode child, List<SortKey> keys) implements PlanNode {
        @Override
        public List<String> schema() {
            return child.schema();
        }

        @Override
        public List<PlanNode> children() {
            return List.of(child());
        }

        @Override
        public String label() {
            return "Sort(keys=" + keys.size() + ")";
        }

        @Override
        public String explain(int indent, StringBuilder sb) {
            sb.append(" ".repeat(indent)).append(label()).append('\n');
            return child.explain(indent + 2, sb);
        }
    }

    public record Limit(PlanNode child, int limit) implements PlanNode {
        public Limit {
            if (limit < 0) {
                throw new IllegalArgumentException("limit 非负约束");
            }
        }

        @Override
        public List<String> schema() {
            return child.schema();
        }

        @Override
        public List<PlanNode> children() {
            return List.of(child());
        }

        @Override
        public String label() {
            return "Limit(" + limit + ")";
        }

        @Override
        public String explain(int indent, StringBuilder sb) {
            sb.append(" ".repeat(indent)).append(label()).append('\n');
            return child.explain(indent + 2, sb);
        }
    }

    /** explain 文本化（顶层入口） */
    public static String explain(PlanNode plan) {
        return plan.explain(0, new StringBuilder()).stripTrailing();
    }
}
