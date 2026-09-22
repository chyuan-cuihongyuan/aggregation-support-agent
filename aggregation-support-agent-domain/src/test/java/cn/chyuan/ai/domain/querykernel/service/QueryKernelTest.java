package cn.chyuan.ai.domain.querykernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询执行内核 BL1-BL7 单测（工单 0533-0539）：
 * 计划 IR/表达式三值逻辑/规则重写/向量化扫描/哈希聚合/哈希连接排序 TopN/代价统计。
 */
class QueryKernelTest {

    private ColumnBatch orders() {
        return new ColumnBatch(List.of("oid", "uid", "amount"), List.of(
                List.of(1, 2, 3, 4, 5),
                java.util.Arrays.asList(10, 10, 20, 30, null),
                List.of(100.0d, 200.0d, 50.0d, 300.0d, 80.0d)));
    }

    private ColumnBatch users() {
        return new ColumnBatch(List.of("uid", "uname"), List.of(
                List.of(10, 20, 40),
                List.of("alice", "bob", "carol")));
    }

    @Test
    void BL1_计划IR_schema传播与explain() {
        LogicalPlan.Scan scan = new LogicalPlan.Scan("orders", List.of("oid", "uid", "amount"), 5);
        LogicalPlan.Filter filter = new LogicalPlan.Filter(scan, new ExprEval.Lit(Boolean.TRUE));
        LogicalPlan.Project project = new LogicalPlan.Project(filter,
                List.of(new ExprEval.ColRef(0, "oid")), List.of("oid"));
        assertEquals(List.of("oid", "uid", "amount"), filter.schema(), "Filter 透传 schema");
        assertEquals(List.of("oid"), project.schema());
        LogicalPlan.Join join = new LogicalPlan.Join(scan, new LogicalPlan.Scan("users",
                List.of("uid", "uname"), 3), 1, 0, false);
        assertEquals(List.of("oid", "uid", "amount", "uid", "uname"), join.schema(), "Join 左右拼接 schema");
        String explain = LogicalPlan.explain(project);
        assertTrue(explain.contains("Project(oid)"), explain);
        assertTrue(explain.contains("Scan(orders rows=5)"), explain);
        assertThrows(IllegalArgumentException.class, () -> new LogicalPlan.Project(scan,
                List.of(new ExprEval.ColRef(0, "oid")), List.of()), "表达式与列名数不一致拒绝");
        assertThrows(IllegalArgumentException.class, () -> new LogicalPlan.Limit(scan, -1), "limit 负数拒绝");
    }

    @Test
    void BL2_表达式求值与三值逻辑() {
        ExprEval eval = new ExprEval();
        ExprEval.Expr eq = new ExprEval.Call("=", List.of(new ExprEval.ColRef(0, "a"), new ExprEval.Lit(10)));
        assertEquals(Boolean.TRUE, eval.eval(eq, List.of(10, 20)));
        assertEquals(Boolean.FALSE, eval.eval(eq, List.of(30, 20)));
        assertNull(eval.eval(eq, java.util.Arrays.asList(null, 20)), "NULL 比较为 UNKNOWN");
        ExprEval.Expr and = new ExprEval.Call("AND", List.of(eq, new ExprEval.Lit(Boolean.FALSE)));
        assertEquals(Boolean.FALSE, eval.eval(and, java.util.Arrays.asList(null, 20)), "AND 见 false 即 false");
        ExprEval.Expr or = new ExprEval.Call("OR", List.of(eq, new ExprEval.Lit(Boolean.TRUE)));
        assertEquals(Boolean.TRUE, eval.eval(or, java.util.Arrays.asList(null, 20)), "OR 见 true 即 true");
        ExprEval.Expr not = new ExprEval.Call("NOT", List.of(eq));
        assertNull(eval.eval(not, java.util.Arrays.asList(null, 20)), "NOT UNKNOWN 仍 UNKNOWN");
        ExprEval.Expr sum = new ExprEval.Call("+", List.of(new ExprEval.ColRef(0, "a"), new ExprEval.Lit(5)));
        assertEquals(15.0d, eval.eval(sum, List.of(10, 0)));
        assertNull(eval.eval(sum, java.util.Arrays.asList(null, 0)), "算术 NULL 传播");
        ExprEval.Expr div = new ExprEval.Call("/", List.of(new ExprEval.Lit(1), new ExprEval.Lit(0)));
        assertThrows(ArithmeticException.class, () -> eval.eval(div, List.of()), "除零拒绝");
        ExprEval.Expr badCmp = new ExprEval.Call("<", List.of(new ExprEval.Lit("a"), new ExprEval.Lit(1)));
        assertThrows(IllegalArgumentException.class, () -> eval.eval(badCmp, List.of()), "类型不可比较拒绝");
        assertThrows(IllegalArgumentException.class, () -> eval.eval(new ExprEval.ColRef(9, "x"), List.of(1)),
                "列越界拒绝");
    }

    @Test
    void BL3_规则重写_下推合并折叠() {
        LogicalPlan.Scan left = new LogicalPlan.Scan("orders", List.of("oid", "uid", "amount"), 5);
        LogicalPlan.Scan right = new LogicalPlan.Scan("users", List.of("uid", "uname"), 3);
        LogicalPlan.Join join = new LogicalPlan.Join(left, right, 1, 0, false);
        // Filter(Join) 中 uid=10（左列 1）下推左测；uname='bob'（右列 4）下推右测并平移列号
        ExprEval.Expr uidEq = new ExprEval.Call("=", List.of(new ExprEval.ColRef(1, "uid"), new ExprEval.Lit(10)));
        ExprEval.Expr unameEq = new ExprEval.Call("=", List.of(new ExprEval.ColRef(4, "uname"),
                new ExprEval.Lit("alice")));
        LogicalPlan.Filter top = new LogicalPlan.Filter(join,
                new ExprEval.Call("AND", List.of(uidEq, unameEq)));
        RuleRewriter rewriter = new RuleRewriter();
        LogicalPlan.PlanNode rewritten = rewriter.rewrite(top);
        assertTrue(rewritten instanceof LogicalPlan.Join, "两侧谓词全部下推后顶部无残留 Filter");
        LogicalPlan.PlanNode newLeft = ((LogicalPlan.Join) rewritten).left();
        assertTrue(newLeft instanceof LogicalPlan.Filter, "左测谓词下推");
        LogicalPlan.PlanNode newRight = ((LogicalPlan.Join) rewritten).right();
        assertTrue(newRight instanceof LogicalPlan.Filter, "右测谓词下推（列号已平移）");
        // 相邻 Filter 合并
        LogicalPlan.Filter outer = new LogicalPlan.Filter(new LogicalPlan.Filter(left, uidEq),
                new ExprEval.Lit(Boolean.TRUE));
        LogicalPlan.PlanNode merged = rewriter.mergeFilters(rewriter.mergeFilters(outer));
        assertTrue(merged instanceof LogicalPlan.Filter && ((LogicalPlan.Filter) merged).child() instanceof LogicalPlan.Scan,
                "Filter(TRUE) 折叠消除");
        // 执行等价（引擎见 BL8 管线测试）
        QueryPort.InMemoryEngine engine = new QueryPort.InMemoryEngine();
        engine.registerTable("orders", orders());
        engine.registerTable("users", users());
        assertEquals(engine.execute(top).rowCount(), engine.execute(rewritten).rowCount(), "重写前后结果行数等价");
    }

    @Test
    void BL4_向量化扫描与选择向量() {
        ColumnBatch batch = orders();
        ExprEval eval = new ExprEval();
        ExprEval.Expr amountGt = new ExprEval.Call(">", List.of(new ExprEval.ColRef(2, "amount"),
                new ExprEval.Lit(90.0d)));
        List<Integer> selection = batch.select(amountGt, eval);
        assertEquals(List.of(0, 1, 3), selection, "命中行升序；NULL 不通过");
        ColumnBatch picked = batch.materialize(selection);
        assertEquals(3, picked.rowCount());
        assertEquals(300.0d, picked.column(2).get(2));
        assertEquals(0, ColumnBatch.empty(batch.names()).rowCount(), "空批合法");
        assertThrows(IllegalArgumentException.class, () -> new ColumnBatch(List.of("a"), List.of()),
                "列长不一致拒绝");
    }

    @Test
    void BL5_哈希聚合与having() {
        HashAggregator agg = new HashAggregator();
        ExprEval eval = new ExprEval();
        List<LogicalPlan.AggSpec> aggs = List.of(
                new LogicalPlan.AggSpec("SUM", 2, "total"),
                new LogicalPlan.AggSpec("COUNT", 2, "cnt"),
                new LogicalPlan.AggSpec("MAX", 2, "max_amt"));
        ColumnBatch out = agg.aggregate(orders(), List.of(1), aggs, null, eval);
        assertEquals(List.of("uid", "total", "cnt", "max_amt"), out.names());
        assertEquals(4, out.rowCount(), "uid=10/20/30/null 四组（NULL 键单列一组）");
        ExprEval.Expr having = new ExprEval.Call(">=", List.of(new ExprEval.ColRef(2, "cnt"), new ExprEval.Lit(2L)));
        ColumnBatch filtered = agg.aggregate(orders(), List.of(1), aggs, having, eval);
        assertEquals(1, filtered.rowCount(), "having 只留 cnt>=2 的组");
        assertEquals(10, filtered.column(0).get(0));
        ColumnBatch global = agg.aggregate(orders(), List.of(), aggs, null, eval);
        assertEquals(1, global.rowCount(), "无分组恒一行");
        assertEquals(5L, global.column(1).get(0), "全局聚合 schema 仅聚合列：COUNT 非空计 5");
        ColumnBatch emptyGlobal = agg.aggregate(ColumnBatch.empty(orders().names()), List.of(), aggs, null, eval);
        assertEquals(1, emptyGlobal.rowCount());
        assertEquals(0L, emptyGlobal.column(1).get(0), "空输入 COUNT=0");
        assertNull(emptyGlobal.column(0).get(0), "空输入 SUM=NULL");
        assertThrows(IllegalArgumentException.class, () -> agg.aggregate(orders(), List.of(1),
                List.of(new LogicalPlan.AggSpec("BAD", 2, "x")), null, eval), "非法聚合函数拒绝");
    }

    @Test
    void BL6_哈希连接_排序与TopN() {
        HashJoinAndTopN ops = new HashJoinAndTopN();
        ColumnBatch joined = ops.join(orders(), users(), 1, 0, false);
        assertEquals(3, joined.rowCount(), "uid=40 无订单不出现；NULL 键不连接");
        assertEquals("alice", joined.column(4).get(0));
        ColumnBatch outer = ops.join(orders(), users(), 1, 0, true);
        assertEquals(5, outer.rowCount(), "左外连接未命中补空行");
        assertNull(outer.column(4).get(4), "补空右侧为 NULL");
        List<List<Object>> rows = new java.util.ArrayList<>();
        for (int r = 0; r < orders().rowCount(); r++) {
            rows.add(orders().row(r));
        }
        List<List<Object>> sorted = ops.sort(rows, List.of(new LogicalPlan.SortKey(1, true)));
        assertNull(sorted.get(0).get(1), "升序 null 最小居首");
        assertEquals(10, sorted.get(1).get(1));
        List<List<Object>> byAmount = ops.sort(rows, List.of(new LogicalPlan.SortKey(2, true)));
        assertEquals(50.0d, byAmount.get(0).get(2), "金额升序最小居首");
        List<List<Object>> desc = ops.sort(rows, List.of(new LogicalPlan.SortKey(2, false)));
        assertEquals(300.0d, desc.get(0).get(2), "降序最大居首");
        assertEquals(50.0d, desc.get(desc.size() - 1).get(2), "降序最小居末");
        List<List<Object>> top2 = ops.topN(rows, new LogicalPlan.SortKey(2, true), 2);
        assertEquals(2, top2.size());
        assertEquals(50.0d, top2.get(0).get(2), "TopN 最小二行");
        assertThrows(IllegalArgumentException.class, () -> ops.topN(rows, new LogicalPlan.SortKey(2, true), -1),
                "TopN 负数拒绝");
    }

    @Test
    void BL7_代价统计与explain() {
        CostEstimator estimator = new CostEstimator();
        estimator.register("orders", 1000L, Map.of("uid", 50L, "amount", 100L));
        LogicalPlan.Scan scan = new LogicalPlan.Scan("orders", List.of("oid", "uid", "amount"), 1000);
        ExprEval.Expr uidEq = new ExprEval.Call("=", List.of(new ExprEval.ColRef(1, "uid"), new ExprEval.Lit(10)));
        LogicalPlan.Filter filter = new LogicalPlan.Filter(scan, uidEq);
        assertEquals(100.0d, estimator.estimate(filter), 0.001d, "等值选择率 1/10");
        ExprEval.Expr amountGt = new ExprEval.Call(">", List.of(new ExprEval.ColRef(2, "amount"),
                new ExprEval.Lit(90.0d)));
        LogicalPlan.Filter range = new LogicalPlan.Filter(scan, amountGt);
        assertEquals(1000.0d / 3.0d, estimator.estimate(range), 0.001d, "范围选择率 1/3");
        assertTrue(estimator.rewriteWins(100.0d, 80.0d), "重写后更小为胜");
        assertFalse(estimator.rewriteWins(80.0d, 100.0d));
        String explain = estimator.explain(filter);
        assertTrue(explain.contains("EstimatedRows"), explain);
        assertTrue(estimator.explain(range).contains("Scan(orders"), explain);
    }
}
