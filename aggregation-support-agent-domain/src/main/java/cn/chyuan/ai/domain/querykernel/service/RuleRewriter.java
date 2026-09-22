package cn.chyuan.ai.domain.querykernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 规则重写（工单 0535 BL3，duckdb 优化器规则思想）。
 * 相邻过滤合并（Filter(Filter(x,a),b)→AND）/谓词下推（AND 合取式拆分按引用列
 * 分侧穿 join，右测列号平移）/常量折叠（Filter(TRUE) 消除）/
 * 重写返回新树不改原计划，等价性由执行器对照断言。
 */
public final class RuleRewriter {

    /** 顶层重写：合并相邻 Filter → 谓词下推 → 常量折叠 */
    public LogicalPlan.PlanNode rewrite(LogicalPlan.PlanNode plan) {
        return foldConstants(pushdown(mergeFilters(plan)));
    }

    /** 相邻 Filter 合并（递归至无可合并） */
    public LogicalPlan.PlanNode mergeFilters(LogicalPlan.PlanNode plan) {
        if (plan instanceof LogicalPlan.Filter filter) {
            LogicalPlan.PlanNode child = mergeFilters(filter.child());
            if (child instanceof LogicalPlan.Filter inner) {
                return mergeFilters(new LogicalPlan.Filter(inner.child(),
                        new ExprEval.Call("AND", List.of(inner.predicate(), filter.predicate()))));
            }
            return new LogicalPlan.Filter(child, filter.predicate());
        }
        return mapChildren(plan, this::mergeFilters);
    }

    /** 谓词下推：Filter(Join(l,r)) 按合取式引用列分侧下推，右测列号左移左表宽度 */
    public LogicalPlan.PlanNode pushdown(LogicalPlan.PlanNode plan) {
        if (plan instanceof LogicalPlan.Filter filter
                && filter.child() instanceof LogicalPlan.Join join) {
            int leftWidth = join.left().schema().size();
            List<ExprEval.Expr> conjuncts = new ArrayList<>();
            splitConjuncts(filter.predicate(), conjuncts);
            List<ExprEval.Expr> toLeft = new ArrayList<>();
            List<ExprEval.Expr> toRight = new ArrayList<>();
            List<ExprEval.Expr> keep = new ArrayList<>();
            for (ExprEval.Expr conjunct : conjuncts) {
                boolean refsLeft = refsColumn(conjunct, 0, leftWidth);
                boolean refsRight = refsColumn(conjunct, leftWidth, Integer.MAX_VALUE);
                if (refsLeft && !refsRight) {
                    toLeft.add(conjunct);
                } else if (refsRight && !refsLeft) {
                    toRight.add(shiftColumns(conjunct, -leftWidth));
                } else {
                    keep.add(conjunct);
                }
            }
            LogicalPlan.PlanNode newLeft = pushdown(wrapFilter(join.left(), toLeft));
            LogicalPlan.PlanNode newRight = pushdown(wrapFilter(join.right(), toRight));
            LogicalPlan.Join newJoin = new LogicalPlan.Join(newLeft, newRight, join.leftKey(), join.rightKey(),
                    join.leftOuter());
            return wrapFilter(newJoin, keep);
        }
        return mapChildren(plan, this::pushdown);
    }

    /** 常量折叠：Filter(TRUE) 消除；Filter(FALSE/NULL) 保留为空门卫 */
    public LogicalPlan.PlanNode foldConstants(LogicalPlan.PlanNode plan) {
        if (plan instanceof LogicalPlan.Filter filter
                && filter.predicate() instanceof ExprEval.Lit lit) {
            if (Boolean.TRUE.equals(lit.value())) {
                return foldConstants(filter.child());
            }
            return new LogicalPlan.Filter(foldConstants(filter.child()), lit);
        }
        return mapChildren(plan, this::foldConstants);
    }

    private LogicalPlan.PlanNode wrapFilter(LogicalPlan.PlanNode node, List<ExprEval.Expr> conjuncts) {
        if (conjuncts.isEmpty()) {
            return node;
        }
        return new LogicalPlan.Filter(node, conjuncts.size() == 1 ? conjuncts.get(0)
                : new ExprEval.Call("AND", List.copyOf(conjuncts)));
    }

    private void splitConjuncts(ExprEval.Expr expr, List<ExprEval.Expr> out) {
        if (expr instanceof ExprEval.Call call && "AND".equals(call.op())) {
            for (ExprEval.Expr arg : call.args()) {
                splitConjuncts(arg, out);
            }
        } else {
            out.add(expr);
        }
    }

    /** 表达式是否引用 [from,to) 界内的列号 */
    private boolean refsColumn(ExprEval.Expr expr, int from, int to) {
        if (expr instanceof ExprEval.ColRef col) {
            return col.index() >= from && col.index() < to;
        }
        if (expr instanceof ExprEval.Call call) {
            for (ExprEval.Expr arg : call.args()) {
                if (refsColumn(arg, from, to)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 列号整体平移（右测谓词下推对齐子计划列空间） */
    private ExprEval.Expr shiftColumns(ExprEval.Expr expr, int delta) {
        if (expr instanceof ExprEval.ColRef col) {
            return new ExprEval.ColRef(col.index() + delta, col.name());
        }
        if (expr instanceof ExprEval.Call call) {
            List<ExprEval.Expr> shifted = new ArrayList<>(call.args().size());
            for (ExprEval.Expr arg : call.args()) {
                shifted.add(shiftColumns(arg, delta));
            }
            return new ExprEval.Call(call.op(), List.copyOf(shifted));
        }
        return expr;
    }

    private LogicalPlan.PlanNode mapChildren(LogicalPlan.PlanNode plan, Function<LogicalPlan.PlanNode,
            LogicalPlan.PlanNode> transform) {
        List<LogicalPlan.PlanNode> children = plan.children();
        if (children.isEmpty()) {
            return plan;
        }
        List<LogicalPlan.PlanNode> rebuilt = new ArrayList<>(children.size());
        for (LogicalPlan.PlanNode child : children) {
            rebuilt.add(transform.apply(child));
        }
        return switch (plan) {
            case LogicalPlan.Filter f -> new LogicalPlan.Filter(rebuilt.get(0), f.predicate());
            case LogicalPlan.Project p -> new LogicalPlan.Project(rebuilt.get(0), p.exprs(), p.names());
            case LogicalPlan.Aggregate a -> new LogicalPlan.Aggregate(rebuilt.get(0), a.groupCols(), a.aggs());
            case LogicalPlan.Join j -> new LogicalPlan.Join(rebuilt.get(0), rebuilt.get(1), j.leftKey(),
                    j.rightKey(), j.leftOuter());
            case LogicalPlan.Sort s -> new LogicalPlan.Sort(rebuilt.get(0), s.keys());
            case LogicalPlan.Limit l -> new LogicalPlan.Limit(rebuilt.get(0), l.limit());
            case LogicalPlan.Scan scan -> scan;
        };
    }
}
