package cn.chyuan.ai.domain.querykernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询端口+组合管线（工单 0540 BL8）。
 * QueryPort（注册内存表→计划执行→列批结果）+内存假实现（七算子解释执行器）+
 * 组合管线（建表→写入→过滤聚合连接查询）/query-kernel.enabled 默认关
 * （开启才改变行为）。纯内存列式，无磁盘溢写（0523-D3）。
 */
public interface QueryPort {

    /** 注册内存表（重名替换） */
    void registerTable(String name, ColumnBatch table);

    /** 按计划执行查询 */
    ColumnBatch execute(LogicalPlan.PlanNode plan);

    /** 规则重写后等价执行（重写→执行一步到位） */
    ColumnBatch executeRewritten(LogicalPlan.PlanNode plan);

    /** 内存假实现：ExprEval + ColumnBatch + HashAggregator + HashJoinAndTopN 解释执行 */
    class InMemoryEngine implements QueryPort {

        private final Map<String, ColumnBatch> tables = new LinkedHashMap<>();
        private final ExprEval eval = new ExprEval();
        private final RuleRewriter rewriter = new RuleRewriter();
        private final HashAggregator aggregator = new HashAggregator();
        private final HashJoinAndTopN joiner = new HashJoinAndTopN();

        @Override
        public synchronized void registerTable(String name, ColumnBatch table) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("表名不得为空");
            }
            tables.put(name, table);
        }

        @Override
        public synchronized ColumnBatch execute(LogicalPlan.PlanNode plan) {
            return switch (plan) {
                case LogicalPlan.Scan scan -> {
                    ColumnBatch table = tables.get(scan.table());
                    if (table == null) {
                        throw new IllegalArgumentException("未注册表: " + scan.table());
                    }
                    yield table;
                }
                case LogicalPlan.Filter filter -> {
                    ColumnBatch child = execute(filter.child());
                    yield child.materialize(child.select(filter.predicate(), eval));
                }
                case LogicalPlan.Project project -> project(project);
                case LogicalPlan.Aggregate aggregate -> {
                    ColumnBatch child = execute(aggregate.child());
                    yield aggregator.aggregate(child, aggregate.groupCols(), aggregate.aggs(), null, eval);
                }
                case LogicalPlan.Join join -> {
                    ColumnBatch left = execute(join.left());
                    ColumnBatch right = execute(join.right());
                    yield joiner.join(left, right, join.leftKey(), join.rightKey(), join.leftOuter());
                }
                case LogicalPlan.Sort sort -> {
                    ColumnBatch child = execute(sort.child());
                    yield materializeRows(child.names(), joiner.sort(rowsAll(child), sort.keys()));
                }
                case LogicalPlan.Limit limit -> {
                    ColumnBatch child = execute(limit.child());
                    List<Integer> head = new ArrayList<>(Math.min(limit.limit(), child.rowCount()));
                    for (int r = 0; r < Math.min(limit.limit(), child.rowCount()); r++) {
                        head.add(r);
                    }
                    yield child.materialize(head);
                }
            };
        }

        @Override
        public synchronized ColumnBatch executeRewritten(LogicalPlan.PlanNode plan) {
            return execute(rewriter.rewrite(plan));
        }

        private ColumnBatch project(LogicalPlan.Project project) {
            ColumnBatch child = execute(project.child());
            List<List<Object>> outCols = new ArrayList<>(project.exprs().size());
            for (int i = 0; i < project.exprs().size(); i++) {
                outCols.add(new ArrayList<>());
            }
            for (int r = 0; r < child.rowCount(); r++) {
                List<Object> row = child.row(r);
                for (int c = 0; c < project.exprs().size(); c++) {
                    outCols.get(c).add(eval.eval(project.exprs().get(c), row));
                }
            }
            return new ColumnBatch(project.names(), outCols);
        }

        private List<List<Object>> rowsAll(ColumnBatch batch) {
            List<List<Object>> rows = new ArrayList<>(batch.rowCount());
            for (int r = 0; r < batch.rowCount(); r++) {
                rows.add(batch.row(r));
            }
            return rows;
        }

        private ColumnBatch materializeRows(List<String> names, List<List<Object>> rows) {
            List<List<Object>> cols = new ArrayList<>(names.size());
            for (int i = 0; i < names.size(); i++) {
                cols.add(new ArrayList<>(rows.size()));
            }
            for (List<Object> row : rows) {
                for (int c = 0; c < row.size(); c++) {
                    cols.get(c).add(row.get(c));
                }
            }
            return new ColumnBatch(names, cols);
        }
    }
}
