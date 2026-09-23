package cn.chyuan.ai.domain.solverkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 求解端口组合管线测试（工单 0601 BS8）。
 * solver-kernel.enabled 默认关（开启才改变行为）；
 * 与 jobkernel 只读联动：jobkernel 形状任务清单作排班输入可选形态。
 */
class SolverPortPipelineTest {

    private static LongSupplier clock() {
        AtomicLong tick = new AtomicLong(5000L);
        return tick::incrementAndGet;
    }

    @Test
    void schedulePipelineMinimizesMakespanUnderCapacity() {
        SolverPort port = new SolverPort.InMemorySolver();
        List<SolverPort.JobShape> jobs = List.of(
                new SolverPort.JobShape("job-index-build", 4, 2, 0, 10),
                new SolverPort.JobShape("job-vector-sync", 3, 2, 0, 10),
                new SolverPort.JobShape("job-report-gen", 2, 1, 0, 10));
        SolverPort.ScheduleOutcome outcome = port.schedule(jobs, 2, clock());
        assertEquals(CpModel.Status.SAT, outcome.solution().status());
        assertTrue(outcome.solution().optimal(), "应给出最优 makespan");
        long finish = outcome.solution().objectiveValue();
        assertEquals(9L, finish, "容量 2 下三任务需求两两超容必串行：makespan=4+3+2");
        assertEquals(finish, maxEnd(jobs, outcome), "finish 应等于实际最大结束时刻");
        assertTrue(noCapacityBreach(jobs, outcome, 2), "任意时刻资源占用不得超容量");
        assertEquals(0L, java.util.Collections.min(outcome.starts().values()),
                "最早任务应从 0 时刻开始");
    }

    @Test
    void schedulePipelineRejectsIllegalInputs() {
        SolverPort port = new SolverPort.InMemorySolver();
        assertThrows(IllegalArgumentException.class, () -> port.schedule(List.of(), 1, clock()));
        assertThrows(IllegalArgumentException.class,
                () -> port.schedule(List.of(new SolverPort.JobShape("bad", 1, 1, 5, 2)), 1, clock()));
        assertThrows(IllegalArgumentException.class, () -> port.schedule(null, 1, clock()));
        assertThrows(IllegalArgumentException.class, () -> port.solve(null, clock()));
    }

    @Test
    void solvePipelineDelegatesToModel() {
        SolverPort port = new SolverPort.InMemorySolver();
        CpModel model = new CpModel();
        model.var("a", 0, 9).var("b", 0, 9);
        model.addAllDifferent(List.of("a", "b"));
        model.addLinear(LinearExpr.of(0, new LinearExpr.Term("a", 1),
                new LinearExpr.Term("b", 1)).eq(7));
        CpModel.Solution solution = port.solve(model, clock());
        assertEquals(CpModel.Status.SAT, solution.status());
        assertEquals(7L, solution.assignments().get("a") + solution.assignments().get("b"));
        assertNotEquals(solution.assignments().get("a"), solution.assignments().get("b"));
    }

    @Test
    void impossibleWindowsSurfaceAsUnsatOrInfeasible() {
        SolverPort port = new SolverPort.InMemorySolver();
        List<SolverPort.JobShape> clash = List.of(
                new SolverPort.JobShape("t1", 5, 3, 0, 2),
                new SolverPort.JobShape("t2", 5, 3, 0, 2));
        SolverPort.ScheduleOutcome outcome = port.schedule(clash, 3, clock());
        assertNotEquals(CpModel.Status.SAT, outcome.solution().status(),
                "容量 3×两任务需求 3 同窗必冲突");
    }

    private static long maxEnd(List<SolverPort.JobShape> jobs, SolverPort.ScheduleOutcome outcome) {
        long max = 0;
        for (SolverPort.JobShape job : jobs) {
            max = Math.max(max, outcome.starts().get(job.taskId()) + job.duration());
        }
        return max;
    }

    private static boolean noCapacityBreach(List<SolverPort.JobShape> jobs,
                                            SolverPort.ScheduleOutcome outcome, long capacity) {
        for (long t = 0; t < 40; t++) {
            long used = 0;
            for (SolverPort.JobShape job : jobs) {
                long start = outcome.starts().get(job.taskId());
                if (t >= start && t < start + job.duration()) {
                    used += job.demand();
                }
            }
            if (used > capacity) {
                return false;
            }
        }
        return true;
    }
}
