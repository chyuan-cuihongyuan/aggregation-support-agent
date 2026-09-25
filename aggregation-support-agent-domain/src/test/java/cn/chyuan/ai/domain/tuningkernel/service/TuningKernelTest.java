package cn.chyuan.ai.domain.tuningkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 超参优化内核测试（工单 0809-0816 CR1-CR8，optuna 思想）。
 * study·trial 生命周期与方向最佳/搜索空间校验与确定性采样/随机与网格/TPE l·g 分组似然比/中位数与逐次减半剪枝/预算早停/端口编排与序列目标。
 */
class TuningKernelTest {

    private static Space quadSpace() {
        return new Space().add(new Space.IntP("x", 0, 6, false));
    }

    @Test
    void studyLifecycleAndDirection() {
        Study max = new Study(Study.Direction.MAXIMIZE, 10, 3);
        Study.Trial t1 = max.ask(Map.of("x", 1L));
        assertEquals(Study.TrialState.RUNNING, t1.state);
        assertEquals(0, t1.number, "编号单调");
        max.complete(t1, 5.0);
        assertEquals(Study.TrialState.COMPLETE, t1.state);
        Study.Trial t2 = max.ask(Map.of("x", 2L));
        max.prune(t2);
        Study.Trial t3 = max.ask(Map.of("x", 3L));
        max.fail(t3);
        Study.Trial t4 = max.ask(Map.of("x", 4L));
        max.complete(t4, 9.0);
        assertEquals(9.0, max.best().get().value);
        assertEquals(4L, max.best().get().params.get("x"));
        assertThrows(IllegalStateException.class, () -> max.complete(t2, 1.0), "终态再 tell 拒绝");
        Study min = new Study(Study.Direction.MINIMIZE, 10, 3);
        Study.Trial m1 = min.ask(Map.of("x", 1L));
        min.complete(m1, 7.0);
        Study.Trial m2 = min.ask(Map.of("x", 2L));
        min.complete(m2, 2.0);
        assertEquals(2.0, min.best().get().value, "minimize 取更低");
    }

    @Test
    void bestTieKeepsFirst() {
        Study study = new Study(Study.Direction.MAXIMIZE, 10, 3);
        Study.Trial a = study.ask(Map.of("x", 1L));
        study.complete(a, 5.0);
        Study.Trial b = study.ask(Map.of("x", 9L));
        study.complete(b, 5.0);
        assertEquals(1L, study.best().get().params.get("x"), "并列取先到");
    }

    @Test
    void spaceValidationAndSampling() {
        assertThrows(IllegalArgumentException.class, () -> new Space.IntP("x", 5, 1, false), "int 越界拒绝");
        assertThrows(IllegalArgumentException.class, () -> new Space.FloatP("y", 1, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new Space.IntP("x", 0, 10, true), "log int 下界须正");
        assertThrows(IllegalArgumentException.class, () -> new Space.CatP("c", List.of()), "空 categorical 拒绝");
        Space space = new Space()
                .add(new Space.IntP("x", 0, 10, false))
                .add(new Space.FloatP("lr", 0.0001, 1.0, true))
                .add(new Space.CatP("opt", List.of("sgd", "adam")));
        assertEquals(3, space.size());
        assertEquals(3, space.snapshot().size(), "空间快照");
        Map<String, Object> s1 = space.sample(new java.util.Random(7));
        Map<String, Object> s2 = space.sample(new java.util.Random(7));
        assertEquals(s1, s2, "同种子确定性");
        assertTrue((Long) s1.get("x") >= 0 && (Long) s1.get("x") <= 10, "int 值域内");
        assertTrue((Double) s1.get("lr") >= 0.0001 && (Double) s1.get("lr") <= 1.0, "float log 值域内");
        assertTrue(List.of("sgd", "adam").contains(s1.get("opt")));
    }

    @Test
    void gridSamplerEnumerateAndExhaust() {
        Space space = new Space()
                .add(new Space.IntP("x", 0, 1, false))
                .add(new Space.CatP("m", List.of("a", "b", "c")));
        Samplers.GridSampler grid = new Samplers.GridSampler();
        assertNotNull(grid.sample(space, null));
        assertEquals(5, grid.remaining(), "2×3 全排列首取后余 5");
        java.util.Set<Map<String, Object>> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 5; i++) {
            seen.add(grid.sample(space, null));
        }
        assertEquals(6, seen.size() + 1, "组合不重复");
        assertEquals(0, grid.remaining());
        assertThrows(IllegalStateException.class, () -> grid.sample(space, null), "网格耗尽拒绝");
    }

    @Test
    void randomSamplerDeterministic() {
        Samplers.RandomSampler r1 = new Samplers.RandomSampler(42);
        Samplers.RandomSampler r2 = new Samplers.RandomSampler(42);
        Space space = quadSpace();
        for (int i = 0; i < 5; i++) {
            assertEquals(r1.sample(space, null), r2.sample(space, null), "同种子同序列");
        }
    }

    @Test
    void tpeFallbackAndBounds() {
        Space space = new Space().add(new Space.IntP("x", 0, 100, false));
        Study study = new Study(Study.Direction.MAXIMIZE, 50, 100);
        TpeSampler tpe = new TpeSampler(1, 5, 1.0, 24);
        Map<String, Object> fallback = tpe.sample(space, study);
        assertTrue(Space.inBounds(space.param("x"), fallback.get("x")), "样本不足退随机且在界内");
        seedTrials(study, space, new Samplers.RandomSampler(9), 6, x -> (double) x);
        Map<String, Object> informed = tpe.sample(space, study);
        assertTrue(Space.inBounds(space.param("x"), informed.get("x")), "TPE 采样在界内");
    }

    @Test
    void tpeConvergesToGoodRegion() {
        Space space = new Space().add(new Space.IntP("x", 0, 20, false));
        Study study = new Study(Study.Direction.MAXIMIZE, 60, 60);
        TuningPort.OptimizeResult result = TuningPort.inMemory().optimize(study, space,
                new TpeSampler(11, 5, 1.5, 32),
                (params, reporter) -> -Math.pow(((Long) params.get("x")).doubleValue() - 17.0, 2.0),
                1, null);
        assertTrue(result.best().value >= -9.0, "TPE 收敛至 |x-17|≤3，实得 " + result.best().params);
    }

    @Test
    void medianPruner() {
        Study study = new Study(Study.Direction.MAXIMIZE, 20, 100);
        seedCompletedWithIntermediates(study, List.of(1.0, 10.0), List.of(2.0, 20.0));
        Pruners.MedianPruner pruner = new Pruners.MedianPruner();
        Study.Trial worse = study.ask(Map.of("x", 3L));
        worse.intermediates.add(1.5);
        assertTrue(pruner.prune(study, worse, 1.5, 1), "第一步劣于中位 1.5 即剪");
        Study.Trial better = study.ask(Map.of("x", 4L));
        better.intermediates.add(9.0);
        assertFalse(pruner.prune(study, better, 9.0, 1), "优于中位不剪");
        Study empty = new Study(Study.Direction.MAXIMIZE, 20, 100);
        Study.Trial first = empty.ask(Map.of("x", 1L));
        assertFalse(pruner.prune(empty, first, 0.1, 1), "无历史不剪");
    }

    @Test
    void successiveHalvingMilestones() {
        Study study = new Study(Study.Direction.MAXIMIZE, 20, 100);
        Pruners.SuccessiveHalvingPruner pruner = new Pruners.SuccessiveHalvingPruner(2, 1);
        Study.Trial t1 = study.ask(Map.of("x", 1L));
        t1.intermediates.add(5.0);
        assertFalse(pruner.prune(study, t1, 5.0, 1), "首个里程碑记录阈值不剪");
        study.complete(t1, 5.0);
        Study.Trial t2 = study.ask(Map.of("x", 2L));
        t2.intermediates.add(2.0);
        assertTrue(pruner.prune(study, t2, 2.0, 1), "里程碑处劣于阈值剪");
        Study.Trial t3 = study.ask(Map.of("x", 3L));
        t3.intermediates.add(7.0);
        assertFalse(pruner.prune(study, t3, 7.0, 1), "更优晋级");
        assertEquals(7.0, pruner.rungs().get(1), "阈值刷新");
        assertFalse(pruner.prune(study, t3, 7.0, 3), "非里程碑步不剪");
        assertThrows(IllegalArgumentException.class, () -> new Pruners.SuccessiveHalvingPruner(1, 1), "eta<2 拒绝");
    }

    @Test
    void budgetAndEarlyStop() {
        Study study = new Study(Study.Direction.MAXIMIZE, 3, 2);
        study.ask(Map.of("x", 1L));
        assertThrows(IllegalArgumentException.class, () -> new Study(Study.Direction.MAXIMIZE, 0, 1),
                "非法预算构造拒绝");
        study.complete(study.trials().get(0), 5.0);
        Study.Trial t2 = study.ask(Map.of("x", 2L));
        study.complete(t2, 1.0);
        assertFalse(study.stopped(), "未达耐心");
        Study.Trial t3 = study.ask(Map.of("x", 3L));
        study.complete(t3, 2.0);
        assertTrue(study.stopped(), "连续无改进达耐心早停");
        assertThrows(IllegalStateException.class, () -> study.ask(Map.of("x", 4L)), "早停后 ask 拒绝");
    }

    @Test
    void portOptimizeQuadratic() {
        TuningPort port = TuningPort.inMemory();
        Study study = port.createStudy(Study.Direction.MINIMIZE, 20, 20);
        TuningPort.OptimizeResult result = port.optimize(study, quadSpace(),
                new Samplers.GridSampler(),
                (params, reporter) -> Math.pow(((Long) params.get("x")).doubleValue() - 3.0, 2.0),
                1, null);
        assertEquals(3L, result.best().params.get("x"), "网格全枚举命中最优 x=3");
        assertEquals(0.0, result.best().value);
        assertEquals(7, result.trialsRun());
    }

    @Test
    void portSeriesObjectiveLinkage() {
        assertEquals(2.0, TuningPort.seriesObjective(List.of(1, 2, 3)), 1e-9, "采样统计序列均值作目标值");
        assertEquals(5.0, TuningPort.seriesObjective(List.of(4.0, 6.0)), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> TuningPort.seriesObjective(List.of()));
    }

    private static void seedTrials(Study study, Space space, Samplers.RandomSampler sampler,
                                   int count, java.util.function.DoubleUnaryOperator objective) {
        for (int i = 0; i < count; i++) {
            Map<String, Object> params = sampler.sample(space, study);
            Study.Trial trial = study.ask(params);
            study.complete(trial, objective.applyAsDouble(((Long) params.get("x")).doubleValue()));
        }
    }

    private static void seedCompletedWithIntermediates(Study study, List<Double> v1, List<Double> v2) {
        Study.Trial a = study.ask(Map.of("x", 1L));
        a.intermediates.addAll(v1);
        study.complete(a, v1.get(v1.size() - 1));
        Study.Trial b = study.ask(Map.of("x", 2L));
        b.intermediates.addAll(v2);
        study.complete(b, v2.get(v2.size() - 1));
    }
}
