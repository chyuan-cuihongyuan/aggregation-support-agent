package cn.chyuan.ai.domain.querykernel.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代价与统计（工单 0539 BL7，duckdb 代价优化思想）。
 * 表行数与列 NDV 统计登记/过滤选择率（等值 1/NDV、范围 1/3、AND 相乘、OR 相加 clip 1）/
 * 逐算子行数估算/重写前后代价比较/explain 全计划缩进文本。
 */
public final class CostEstimator {

    /** 单表统计 */
    public record TableStats(long rows, Map<String, Long> ndvByColumn) {
    }

    /** 估算代价：输出行数（含中间代价累计） */
    public record Cost(double rows) {
    }

    private final Map<String, TableStats> stats = new LinkedHashMap<>();

    public void register(String table, long rows, Map<String, Long> ndvByColumn) {
        if (rows < 0) {
            throw new IllegalArgumentException("行数非负约束");
        }
        stats.put(table, new TableStats(rows, ndvByColumn));
    }

    public TableStats stats(String table) {
        return stats.get(table);
    }

    /** 计划输出行数估算 */
    public double estimate(LogicalPlan.PlanNode plan) {
        return switch (plan) {
            case LogicalPlan.Scan scan -> stats(scan.table()) != null ? stats(scan.table()).rows() : scan.rows();
            case LogicalPlan.Filter filter -> {
                double sel = selectivity(filter.predicate());
                yield estimate(filter.child()) * sel;
            }
            case LogicalPlan.Project project -> estimate(project.child());
            case LogicalPlan.Aggregate aggregate -> {
                double childRows = estimate(aggregate.child());
                if (aggregate.groupCols().isEmpty()) {
                    yield 1.0d;
                }
                double distinct = 1.0d;
                for (Integer col : aggregate.groupCols()) {
                    distinct *= ndvOf(plan, col);
                }
                yield Math.min(childRows, distinct);
            }
            case LogicalPlan.Join join -> {
                double left = estimate(join.left());
                double right = estimate(join.right());
                // 等值连接经验估算：输出不超过较小侧基数（键-外键形态）
                yield Math.min(left, right);
            }
            case LogicalPlan.Sort sort -> estimate(sort.child());
            case LogicalPlan.Limit limit -> Math.min(estimate(limit.child()), limit.limit());
        };
    }

    /** 重写前后代价比较：返回 true 表示重写后更优（行数估算更小或相等） */
    public boolean rewriteWins(double before, double after) {
        return after <= before;
    }

    private double selectivity(ExprEval.Expr expr) {
        if (expr instanceof ExprEval.Call call) {
            return switch (call.op()) {
                case "AND" -> {
                    double sel = 1.0d;
                    for (ExprEval.Expr arg : call.args()) {
                        sel *= selectivity(arg);
                    }
                    yield sel;
                }
                case "OR" -> {
                    double sel = 0.0d;
                    for (ExprEval.Expr arg : call.args()) {
                        sel += selectivity(arg);
                    }
                    yield Math.min(1.0d, sel);
                }
                case "=" -> 1.0d / 10.0d;
                case "<", "<=", ">", ">=", "!=" -> 1.0d / 3.0d;
                default -> 1.0d;
            };
        }
        if (expr instanceof ExprEval.Lit lit) {
            return Boolean.TRUE.equals(lit.value()) ? 1.0d : 0.0d;
        }
        return 1.0d;
    }

    private double ndvOf(LogicalPlan.PlanNode plan, int col) {
        if (plan instanceof LogicalPlan.Scan scan && stats(scan.table()) != null) {
            String name = scan.columns().get(col);
            Long ndv = stats(scan.table()).ndvByColumn().get(name);
            return ndv != null ? ndv : 10.0d;
        }
        return 10.0d;
    }

    /** explain 全计划缩进文本（LogicalPlan 文本化 + 尾部行数估算行） */
    public String explain(LogicalPlan.PlanNode plan) {
        String tree = LogicalPlan.explain(plan);
        return tree + "\nEstimatedRows(" + String.format("%.2f", estimate(plan)) + ")";
    }
}
