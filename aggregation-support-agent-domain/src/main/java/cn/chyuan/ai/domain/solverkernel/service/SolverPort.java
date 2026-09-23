package cn.chyuan.ai.domain.solverkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 求解端口+组合管线（工单 0601 BS8）。
 * SolverPort（模型→解+统计）组合管线：排班样例（工人×班次×技能
 * allDifferent+cumulative 容量→目标最小超载）端到端；
 * 与 jobkernel 只读联动（jobkernel 形状任务清单作排班输入可选形态，
 * 泛型入参不 import jobkernel，不改 jobkernel 任何类）/
 * solver-kernel.enabled 默认关（开启才改变行为）。
 */
public interface SolverPort {

    /** jobkernel 形状任务（只读联动输入形态：任务名/时长/资源需求/候选窗口） */
    record JobShape(String taskId, long duration, long demand, long earliestStart, long latestStart) {
    }

    /** 排班产出：任务→开始时刻 + 统计 */
    record ScheduleOutcome(CpModel.Solution solution, Map<String, Long> starts) {
    }

    /** 求解（委托模型求解） */
    CpModel.Solution solve(CpModel model, LongSupplier clock);

    /**
     * 排班组合管线：任务互斥资源（cumulative 容量 1 即串行）+
     * 时间窗约束 → 最小化总完工 makespan（结束时刻最大值代理：
     * 优化 finish 变量并约束每任务 start+duration ≤ finish）。
     */
    ScheduleOutcome schedule(List<JobShape> jobs, long capacity, LongSupplier clock);

    /** 内存假实现：CpModel 全链 */
    class InMemorySolver implements SolverPort {

        @Override
        public synchronized CpModel.Solution solve(CpModel model, LongSupplier clock) {
            if (model == null || clock == null) {
                throw new IllegalArgumentException("模型与时钟不得为 null");
            }
            return model.solve(clock);
        }

        @Override
        public synchronized ScheduleOutcome schedule(List<JobShape> jobs, long capacity, LongSupplier clock) {
            if (jobs == null || jobs.isEmpty()) {
                throw new IllegalArgumentException("任务清单不得为空");
            }
            if (clock == null) {
                throw new IllegalArgumentException("时钟不得为 null");
            }
            CpModel model = new CpModel();
            List<GlobalConstraints.Task> tasks = new ArrayList<>();
            for (JobShape job : jobs) {
                if (job.latestStart() < job.earliestStart()) {
                    throw new IllegalArgumentException("任务时间窗非法：" + job.taskId());
                }
                model.var(job.taskId(), job.earliestStart(), job.latestStart());
                tasks.add(new GlobalConstraints.Task(job.taskId(), job.duration(), job.demand()));
            }
            model.addCumulative(tasks, capacity);
            long horizon = 0;
            for (JobShape job : jobs) {
                horizon = Math.max(horizon, job.latestStart() + job.duration());
            }
            model.var("__finish__", 0, horizon + 1);
            for (JobShape job : jobs) {
                // finish ≥ start_i + duration_i（完工下界逐任务化）
                model.addLinear(LinearExpr.of(job.duration(),
                        new LinearExpr.Term(job.taskId(), 1L),
                        new LinearExpr.Term("__finish__", -1L)).le(0));
            }
            LinearExpr makespan = LinearExpr.of(0, new LinearExpr.Term("__finish__", 1L));
            CpModel.Solution solution = model.optimize(makespan, true, clock);
            Map<String, Long> starts = new LinkedHashMap<>();
            for (JobShape job : jobs) {
                starts.put(job.taskId(), solution.assignments().get(job.taskId()));
            }
            return new ScheduleOutcome(solution, starts);
        }
    }
}
