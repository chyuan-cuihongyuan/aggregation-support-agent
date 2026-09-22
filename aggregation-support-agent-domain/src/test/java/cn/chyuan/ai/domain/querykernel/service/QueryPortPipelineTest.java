package cn.chyuan.ai.domain.querykernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询端口组合管线 BL8 单测（工单 0540）：
 * 建表→写入→过滤聚合连接排序 TopN 全算子组合管线 + 重写等价 + 未注册表拒绝。
 */
class QueryPortPipelineTest {

    private QueryPort.InMemoryEngine engine() {
        QueryPort.InMemoryEngine engine = new QueryPort.InMemoryEngine();
        engine.registerTable("orders", new ColumnBatch(List.of("oid", "uid", "amount"), List.of(
                List.of(1, 2, 3, 4),
                List.of(10, 10, 20, 30),
                List.of(100.0d, 200.0d, 50.0d, 300.0d))));
        engine.registerTable("users", new ColumnBatch(List.of("uid", "uname"), List.of(
                List.of(10, 20),
                List.of("alice", "bob"))));
        return engine;
    }

    /** SELECT u.uname, SUM(o.amount) total FROM orders o JOIN users u ON o.uid=u.uid GROUP BY u.uname */
    private LogicalPlan.PlanNode aggPlan() {
        LogicalPlan.Scan orders = new LogicalPlan.Scan("orders", List.of("oid", "uid", "amount"), 4);
        LogicalPlan.Scan users = new LogicalPlan.Scan("users", List.of("uid", "uname"), 2);
        LogicalPlan.Join join = new LogicalPlan.Join(orders, users, 1, 0, false);
        LogicalPlan.Aggregate aggregate = new LogicalPlan.Aggregate(join, List.of(4),
                List.of(new LogicalPlan.AggSpec("SUM", 2, "total")));
        return new LogicalPlan.Sort(aggregate, List.of(new LogicalPlan.SortKey(0, true)));
    }

    @Test
    void BL8_组合管线_过滤连接聚合排序() {
        QueryPort.InMemoryEngine engine = engine();
        ColumnBatch out = engine.execute(aggPlan());
        assertEquals(List.of("uname", "total"), out.names());
        assertEquals(2, out.rowCount(), "alice(2 单)/bob(1 单) 两组");
        assertEquals(300.0d, out.column(1).get(0), "alice total=100+200");
        assertEquals(50.0d, out.column(1).get(1), "bob total=50");
    }

    @Test
    void BL8_重写等价与Limit投影() {
        QueryPort.InMemoryEngine engine = engine();
        ColumnBatch direct = engine.execute(aggPlan());
        ColumnBatch rewritten = engine.executeRewritten(aggPlan());
        assertEquals(direct.rowCount(), rewritten.rowCount(), "重写前后行数等价");
        assertEquals(direct.column(1).get(0), rewritten.column(1).get(0), "重写前后聚合值等价");
        // 过滤+投影+Limit
        LogicalPlan.Scan orders = new LogicalPlan.Scan("orders", List.of("oid", "uid", "amount"), 4);
        LogicalPlan.Filter filter = new LogicalPlan.Filter(orders, new ExprEval.Call(">",
                List.of(new ExprEval.ColRef(2, "amount"), new ExprEval.Lit(60.0d))));
        LogicalPlan.Project project = new LogicalPlan.Project(filter,
                List.of(new ExprEval.ColRef(0, "oid")), List.of("oid"));
        LogicalPlan.Limit limit = new LogicalPlan.Limit(project, 2);
        ColumnBatch out = engine.execute(limit);
        assertEquals(List.of("oid"), out.names());
        assertEquals(2, out.rowCount(), "amount>60 三行取前二");
    }

    @Test
    void BL8_未注册表拒绝与空表名拒绝() {
        QueryPort.InMemoryEngine engine = engine();
        LogicalPlan.Scan ghost = new LogicalPlan.Scan("ghost", List.of("x"), 0);
        assertThrows(IllegalArgumentException.class, () -> engine.execute(ghost), "未注册表拒绝");
        assertThrows(IllegalArgumentException.class, () -> engine.registerTable("",
                ColumnBatch.empty(List.of("x"))), "空表名拒绝");
    }
}
