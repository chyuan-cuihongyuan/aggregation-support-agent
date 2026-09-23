package cn.chyuan.ai.domain.solverkernel.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 约束模型与分支回溯搜索（工单 0597 BS4 + 0599 BS6 + 0600 BS7，CP-SAT 搜索思想）。
 * 最小域变量选择（tie 按注册序确定性）/二分值序（x≤mid 或 x≥mid+1）/
 * 决策栈域快照回退/不可行回溯剪枝/分支数与冲突计数/节点上限熔断；
 * 目标最优：分支定界（首解→上界收紧→无更优解即 OPTIMAL）；
 * 求解统计：传播轮次/回退数/分支数/耗时（时钟注入）/解状态。
 */
public final class CpModel {

    /** 解状态 */
    public enum Status {
        SAT, UNSAT, NODE_LIMIT
    }

    /** 求解结果：赋值 + 统计摘要 */
    public record Solution(Status status, Map<String, Long> assignments,
                           LinearExpr objective, long objectiveValue,
                           long propagationRounds, long backtracks, long branches,
                           long elapsedMs) {

        public boolean optimal() {
            return status == Status.SAT && objective != null;
        }
    }

    private final Map<String, long[]> domains = new LinkedHashMap<>();
    private final List<PropagationEngine.Propagator> propagators = new java.util.ArrayList<>();

    /** 注册整数变量（重名同域幂等，冲突拒绝） */
    public synchronized CpModel var(String name, long lb, long ub) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("变量名不得为空");
        }
        if (lb > ub) {
            throw new IllegalArgumentException("变量域非法：" + name + " [" + lb + "," + ub + "]");
        }
        long[] existing = domains.get(name);
        if (existing != null && (existing[0] != lb || existing[1] != ub)) {
            throw new IllegalArgumentException("变量重复注册且域冲突：" + name);
        }
        domains.put(name, new long[]{lb, ub});
        return this;
    }

    /** 注册布尔变量（0-1 域） */
    public synchronized CpModel bool(String name) {
        return var(name, 0, 1);
    }

    /** 线性约束 */
    public synchronized CpModel addLinear(LinearExpr.Constraint constraint) {
        if (constraint == null) {
            throw new IllegalArgumentException("约束不得为 null");
        }
        for (String v : constraint.terms().keySet()) {
            if (!domains.containsKey(v)) {
                throw new IllegalArgumentException("未注册变量：" + v);
            }
        }
        propagators.add(PropagationEngine.linear(constraint));
        return this;
    }

    /** 布尔子句 */
    public synchronized CpModel addClause(BoolClauses.Clause clause) {
        if (clause == null) {
            throw new IllegalArgumentException("子句不得为 null");
        }
        for (BoolClauses.Literal lit : clause.literals()) {
            long[] d = domains.get(lit.var());
            if (d == null) {
                throw new IllegalArgumentException("未注册布尔变量：" + lit.var());
            }
            if (d[0] < 0 || d[1] > 1) {
                throw new IllegalArgumentException("子句变量须为 0-1 域：" + lit.var());
            }
        }
        propagators.add(BoolClauses.propagator(clause));
        return this;
    }

    /** allDifferent 全局约束 */
    public synchronized CpModel addAllDifferent(List<String> vars) {
        for (String v : vars) {
            if (!domains.containsKey(v)) {
                throw new IllegalArgumentException("未注册变量：" + v);
            }
        }
        propagators.add(GlobalConstraints.allDifferent(vars));
        return this;
    }

    /** cumulative 全局约束 */
    public synchronized CpModel addCumulative(List<GlobalConstraints.Task> tasks, long capacity) {
        for (GlobalConstraints.Task task : tasks) {
            if (!domains.containsKey(task.startVar())) {
                throw new IllegalArgumentException("未注册变量：" + task.startVar());
            }
        }
        propagators.add(GlobalConstraints.cumulative(tasks, capacity));
        return this;
    }

    /** 可满足性求解（无目标） */
    public synchronized Solution solve(LongSupplier clock) {
        return solveWithObjective(null, false, Long.MAX_VALUE, clock);
    }

    /** 目标最优化（分支定界） */
    public synchronized Solution optimize(LinearExpr objective, boolean minimize, LongSupplier clock) {
        return optimizeWithLimit(objective, minimize, Long.MAX_VALUE, clock);
    }

    /** 目标最优化（节点上限熔断） */
    public synchronized Solution optimizeWithLimit(LinearExpr objective, boolean minimize,
                                                   long nodeLimit, LongSupplier clock) {
        if (objective == null) {
            throw new IllegalArgumentException("目标不得为 null");
        }
        for (String v : objective.terms().keySet()) {
            if (!domains.containsKey(v)) {
                throw new IllegalArgumentException("目标含未注册变量：" + v);
            }
        }
        Solution best = null;
        long bound = Long.MIN_VALUE;
        while (true) {
            LinearExpr.Constraint tighten = minimize
                    ? objective.le(best == null ? Long.MAX_VALUE / 4 : best.objectiveValue() - 1)
                    : objective.ge(best == null ? Long.MIN_VALUE / 4 : best.objectiveValue() + 1);
            List<PropagationEngine.Propagator> saved = new java.util.ArrayList<>(propagators);
            propagators.add(PropagationEngine.linear(tighten));
            Solution candidate = solveWithObjective(objective, minimize, nodeLimit, clock);
            propagators.clear();
            propagators.addAll(saved);
            if (candidate.status() == Status.SAT) {
                best = candidate;
                bound = candidate.objectiveValue();
                continue;
            }
            if (candidate.status() == Status.NODE_LIMIT) {
                Solution limited = new Solution(Status.NODE_LIMIT,
                        best == null ? Map.of() : best.assignments(), objective,
                        best == null ? 0 : bound, candidate.propagationRounds(),
                        candidate.backtracks(), candidate.branches(), candidate.elapsedMs());
                return limited;
            }
            if (best != null) {
                return best;
            }
            return candidate;
        }
    }

    private Solution solveWithObjective(LinearExpr objective, boolean minimize, long nodeLimit,
                                        LongSupplier clock) {
        long start = clock.getAsLong();
        PropagationEngine solveEngine = new PropagationEngine();
        for (PropagationEngine.Propagator propagator : propagators) {
            solveEngine.add(propagator);
        }
        SearchStats stats = new SearchStats();
        Map<String, long[]> work = PropagationEngine.snapshot(domains);
        try {
            solveEngine.fixpoint(work);
            Map<String, Long> assignments = dfs(solveEngine, work, nodeLimit, stats);
            long elapsed = clock.getAsLong() - start;
            return new Solution(Status.SAT, assignments, objective,
                    objective == null ? 0 : eval(objective, assignments),
                    solveEngine.totalRounds(), stats.backtracks, stats.branches, elapsed);
        } catch (PropagationEngine.Infeasible e) {
            long elapsed = clock.getAsLong() - start;
            return new Solution(Status.UNSAT, Map.of(), objective, 0,
                    solveEngine.totalRounds(), stats.backtracks, stats.branches, elapsed);
        } catch (NodeLimitExceeded e) {
            long elapsed = clock.getAsLong() - start;
            return new Solution(Status.NODE_LIMIT, Map.of(), objective, 0,
                    solveEngine.totalRounds(), stats.backtracks, stats.branches, elapsed);
        }
    }

    private Map<String, Long> dfs(PropagationEngine solveEngine, Map<String, long[]> domains,
                                  long nodeLimit, SearchStats stats) {
        stats.nodes++;
        if (stats.nodes > nodeLimit) {
            throw new NodeLimitExceeded();
        }
        solveEngine.fixpoint(domains);
        String branch = PropagationEngine.selectBranchVar(domains);
        if (branch == null) {
            Map<String, Long> assignments = new LinkedHashMap<>();
            for (Map.Entry<String, long[]> e : domains.entrySet()) {
                assignments.put(e.getKey(), e.getValue()[0]);
            }
            return assignments;
        }
        long[] d = domains.get(branch);
        long mid = (d[0] + d[1]) >>> 1;
        stats.branches += 2;
        // 左枝：x ≤ mid
        Map<String, long[]> left = PropagationEngine.snapshot(domains);
        left.get(branch)[1] = mid;
        try {
            return dfs(solveEngine, left, nodeLimit, stats);
        } catch (PropagationEngine.Infeasible | NodeLimitExceeded e) {
            if (e instanceof NodeLimitExceeded) {
                throw e;
            }
            stats.backtracks++;
        }
        // 右枝：x ≥ mid+1
        domains.get(branch)[0] = mid + 1;
        return dfs(solveEngine, domains, nodeLimit, stats);
    }

    private static long eval(LinearExpr expr, Map<String, Long> assignments) {
        long value = expr.constant();
        for (Map.Entry<String, Long> t : expr.terms().entrySet()) {
            value += t.getValue() * assignments.get(t.getKey());
        }
        return value;
    }

    private static final class SearchStats {
        long nodes;
        long backtracks;
        long branches;
    }

    private static final class NodeLimitExceeded extends RuntimeException {
    }

    /** 变量域视图（只读） */
    public synchronized Map<String, long[]> domainsView() {
        return PropagationEngine.snapshot(domains);
    }
}
