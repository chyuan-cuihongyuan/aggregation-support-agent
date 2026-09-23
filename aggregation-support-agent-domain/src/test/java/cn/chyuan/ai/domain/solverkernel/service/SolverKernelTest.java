package cn.chyuan.ai.domain.solverkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 约束求解内核域单测（工单 0594-0600 BS1-BS7，OR-Tools CP-SAT 思想）。
 * 线性表达式归一/CNF 单元传播/区间传播定点/分支回溯/全局约束/
 * 目标最优分支定界/求解统计。
 */
class SolverKernelTest {

    private static LongSupplier clock() {
        AtomicLong tick = new AtomicLong(1000L);
        return tick::incrementAndGet;
    }

    @Test
    void linearExprMergesTermsAndFoldsZeroCoefficients() {
        LinearExpr a = LinearExpr.of(5, new LinearExpr.Term("x", 2), new LinearExpr.Term("y", 3));
        LinearExpr b = LinearExpr.of(-2, new LinearExpr.Term("x", 1), new LinearExpr.Term("z", 0));
        LinearExpr sum = a.plus(b);
        assertEquals(2, sum.variableCount(), "零系数项应折叠");
        assertEquals(3L, sum.terms().get("x"));
        assertEquals(3L, sum.terms().get("y"));
        assertEquals(3L, sum.constant());
        assertEquals(-10L, a.scale(-2).constant());
        assertEquals(4L, a.scale(2).terms().get("x"));
        assertThrows(IllegalArgumentException.class, () -> LinearExpr.of(0, new LinearExpr.Term(" ", 1)));
        assertThrows(IllegalArgumentException.class, () -> a.plus(null));
    }

    @Test
    void boolClausesUnitPropagateAndDetectContradiction() {
        Map<String, long[]> domains = new java.util.LinkedHashMap<>();
        domains.put("a", new long[]{0, 1});
        domains.put("b", new long[]{0, 1});
        BoolClauses.Clause unit = BoolClauses.clause(BoolClauses.pos("a"));
        assertTrue(BoolClauses.propagator(unit).propagate(domains));
        assertEquals(1L, domains.get("a")[0]);

        BoolClauses.Clause taut = BoolClauses.clause(BoolClauses.pos("a"), BoolClauses.neg("a"));
        assertFalse(BoolClauses.propagator(taut).propagate(domains), "重言子句应被吸收");

        domains.get("a")[0] = 0;
        domains.get("a")[1] = 0;
        BoolClauses.Clause forced = BoolClauses.clause(BoolClauses.pos("a"), BoolClauses.pos("b"));
        assertTrue(BoolClauses.propagator(forced).propagate(domains));
        assertEquals(1L, domains.get("b")[0]);

        domains.get("b")[0] = 0;
        domains.get("b")[1] = 0;
        assertThrows(PropagationEngine.Infeasible.class,
                () -> BoolClauses.propagator(forced).propagate(domains));
        assertThrows(IllegalArgumentException.class, () -> BoolClauses.clause());
    }

    @Test
    void linearPropagationTightensToIntervalsAndFailsOnConflict() {
        Map<String, long[]> domains = new java.util.LinkedHashMap<>();
        domains.put("x", new long[]{0, 100});
        domains.put("y", new long[]{0, 100});
        PropagationEngine engine = new PropagationEngine();
        engine.add(PropagationEngine.linear(LinearExpr.of(0,
                new LinearExpr.Term("x", 1), new LinearExpr.Term("y", 1)).eq(10)));
        engine.add(PropagationEngine.linear(LinearExpr.of(0,
                new LinearExpr.Term("x", 1), new LinearExpr.Term("y", -1)).eq(2)));
        long rounds = engine.fixpoint(domains);
        assertTrue(rounds >= 1);
        assertTrue(domains.get("x")[0] >= 2 && domains.get("x")[1] <= 10,
                "x+y=10 应把 x 收进 [2,10]");
        assertTrue(domains.get("y")[0] >= 0 && domains.get("y")[1] <= 8,
                "x−y=2 应把 y 收进 [0,8]");
        assertTrue(domains.get("x")[1] < 100 && domains.get("y")[1] < 100,
                "传播应显著收缩初始域");

        Map<String, long[]> bad = new java.util.LinkedHashMap<>();
        bad.put("x", new long[]{0, 1});
        PropagationEngine failEngine = new PropagationEngine();
        failEngine.add(PropagationEngine.linear(LinearExpr.of(0,
                new LinearExpr.Term("x", 1)).ge(5)));
        assertThrows(PropagationEngine.Infeasible.class, () -> failEngine.fixpoint(bad));

        PropagationEngine missing = new PropagationEngine();
        missing.add(PropagationEngine.linear(LinearExpr.of(0,
                new LinearExpr.Term("nope", 1)).le(1)));
        assertThrows(IllegalArgumentException.class,
                () -> missing.fixpoint(new java.util.LinkedHashMap<>(Map.of("x", new long[]{0, 1}))));
    }

    @Test
    void branchAndBoundSearchSolvesAndProvesUnsat() {
        CpModel model = new CpModel();
        model.var("x", 0, 50).var("y", 0, 50);
        model.addLinear(LinearExpr.of(0, new LinearExpr.Term("x", 1),
                new LinearExpr.Term("y", 1)).eq(10));
        model.addLinear(LinearExpr.of(0, new LinearExpr.Term("x", 1),
                new LinearExpr.Term("y", -1)).eq(2));
        CpModel.Solution solution = model.solve(clock());
        assertEquals(CpModel.Status.SAT, solution.status());
        assertEquals(6L, solution.assignments().get("x"));
        assertEquals(4L, solution.assignments().get("y"));
        assertTrue(solution.branches() > 0);
        assertTrue(solution.elapsedMs() > 0);

        CpModel.Solution again = model.solve(clock());
        assertEquals(solution.assignments(), again.assignments(), "同序求解应确定性");

        CpModel unsat = new CpModel();
        unsat.var("x", 0, 1);
        unsat.addLinear(LinearExpr.of(0, new LinearExpr.Term("x", 1)).ge(3));
        assertEquals(CpModel.Status.UNSAT, unsat.solve(clock()).status());
    }

    @Test
    void globalAllDifferentAndCumulativeEnforce() {
        CpModel tight = new CpModel();
        tight.var("a", 1, 2).var("b", 1, 2).var("c", 1, 2);
        tight.addAllDifferent(List.of("a", "b", "c"));
        assertEquals(CpModel.Status.UNSAT, tight.solve(clock()).status(), "3 变量 2 值必冲突");

        CpModel ok = new CpModel();
        ok.var("a", 1, 3).var("b", 1, 3).var("c", 1, 3);
        ok.addAllDifferent(List.of("a", "b", "c"));
        CpModel.Solution solution = ok.solve(clock());
        assertEquals(CpModel.Status.SAT, solution.status());
        assertEquals(3, java.util.stream.Stream.of("a", "b", "c")
                .map(v -> solution.assignments().get(v)).distinct().count());

        CpModel clash = new CpModel();
        clash.var("s1", 0, 1).var("s2", 0, 1);
        clash.addCumulative(List.of(new GlobalConstraints.Task("s1", 3, 2),
                new GlobalConstraints.Task("s2", 3, 2)), 2);
        assertEquals(CpModel.Status.UNSAT, clash.solve(clock()).status(), "必达段重叠超容量");

        CpModel spaced = new CpModel();
        spaced.var("s1", 0, 2).var("s2", 4, 6);
        spaced.addCumulative(List.of(new GlobalConstraints.Task("s1", 2, 2),
                new GlobalConstraints.Task("s2", 2, 2)), 2);
        assertEquals(CpModel.Status.SAT, spaced.solve(clock()).status());

        assertThrows(IllegalArgumentException.class,
                () -> GlobalConstraints.cumulative(List.of(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> GlobalConstraints.allDifferent(List.of("only")));
    }

    @Test
    void objectiveBranchAndBoundFindsOptimum() {
        CpModel model = new CpModel();
        model.var("x", 0, 10);
        model.addLinear(LinearExpr.of(0, new LinearExpr.Term("x", 1)).ge(3));
        LinearExpr objective = LinearExpr.of(0, new LinearExpr.Term("x", 1));
        CpModel.Solution min = model.optimize(objective, true, clock());
        assertTrue(min.optimal());
        assertEquals(3L, min.objectiveValue());

        CpModel.Solution max = model.optimize(objective, false, clock());
        assertEquals(10L, max.objectiveValue());

        CpModel ratio = new CpModel();
        ratio.var("a", 1, 9).var("b", 1, 9);
        ratio.addLinear(LinearExpr.of(0, new LinearExpr.Term("a", 2),
                new LinearExpr.Term("b", 3)).eq(12));
        CpModel.Solution minSum = ratio.optimize(
                LinearExpr.of(0, new LinearExpr.Term("a", 1), new LinearExpr.Term("b", 1)),
                true, clock());
        assertEquals(5L, minSum.objectiveValue(), "a=3,b=2 为 2a+3b=12 的最小和");

        assertThrows(IllegalArgumentException.class, () -> model.optimize(null, true, clock()));
    }

    @Test
    void nodeLimitFusesAndStatsCarry() {
        CpModel model = new CpModel();
        model.var("x", 0, 1_000_000).var("y", 0, 1_000_000);
        model.addLinear(LinearExpr.of(0, new LinearExpr.Term("x", 7),
                new LinearExpr.Term("y", 11)).eq(1_000_003));
        LinearExpr objective = LinearExpr.of(0, new LinearExpr.Term("x", 1));
        CpModel.Solution limited = model.optimizeWithLimit(objective, true, 3L, clock());
        assertEquals(CpModel.Status.NODE_LIMIT, limited.status());

        CpModel.Solution full = model.optimizeWithLimit(objective, true, 100_000L, clock());
        assertEquals(CpModel.Status.SAT, full.status());
        assertEquals(10L, full.objectiveValue(), "x≡10 (mod 11) 的最小可行 x");
        assertTrue(full.propagationRounds() > 0);
        assertTrue(full.backtracks() >= 0);
        assertTrue(full.branches() >= 0);
    }
}
