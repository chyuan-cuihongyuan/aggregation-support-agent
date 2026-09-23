package cn.chyuan.ai.domain.solverkernel.service;

import java.util.List;
import java.util.Map;

/**
 * 全局约束（工单 0598 BS5，OR-Tools 全局约束思想）。
 * allDifferent（单值域赋值→其他变量域值消去+Hall 区间过滤）/
 * cumulative 时序累计（任务区间×资源 ≤ 容量，必达段占用检测）/
 * 全局约束传播失败即不可行。
 */
public final class GlobalConstraints {

    private GlobalConstraints() {
    }

    /** allDifferent：变量两两不同（区间域去点+Hall 区间检测） */
    public static PropagationEngine.Propagator allDifferent(List<String> vars) {
        if (vars == null || vars.size() < 2) {
            throw new IllegalArgumentException("allDifferent 至少两个变量");
        }
        return domains -> {
            boolean changed = false;
            boolean progressed = true;
            while (progressed) {
                progressed = false;
                for (String v : vars) {
                    requireVar(domains, v);
                }
                // 固定值消去
                for (String fixed : vars) {
                    long[] df = domains.get(fixed);
                    if (df[0] == df[1]) {
                        for (String other : vars) {
                            if (other.equals(fixed)) {
                                continue;
                            }
                            long[] d = domains.get(other);
                            if (d[0] == df[0] && d[1] == df[1] && d[0] == d[1]) {
                                throw new PropagationEngine.Infeasible("allDifferent 值冲突：" + fixed + " 与 " + other);
                            }
                            boolean shrunk = false;
                            if (d[0] == df[0] && d[1] > d[0]) {
                                d[0] = df[0] + 1;
                                shrunk = true;
                            }
                            if (d[1] == df[1] && d[0] < d[1]) {
                                d[1] = df[1] - 1;
                                shrunk = true;
                            }
                            if (shrunk) {
                                if (d[0] > d[1]) {
                                    throw new PropagationEngine.Infeasible("allDifferent 域空：" + other);
                                }
                                changed = true;
                                progressed = true;
                            }
                        }
                    }
                }
                // Hall 区间：子集变量域全落在 [a,b] 且数量 > b-a+1 → 不可行
                for (String lo : vars) {
                    for (String hi : vars) {
                        long a = domains.get(lo)[0];
                        long b = domains.get(hi)[1];
                        if (b < a) {
                            continue;
                        }
                        int inside = 0;
                        for (String v : vars) {
                            long[] d = domains.get(v);
                            if (d[0] >= a && d[1] <= b) {
                                inside++;
                            }
                        }
                        if (inside > b - a + 1) {
                            throw new PropagationEngine.Infeasible(
                                    "allDifferent Hall 区间不足：[" + a + "," + b + "] 塞 " + inside + " 变量");
                        }
                    }
                }
            }
            return changed;
        };
    }

    /** 时序累计任务：start 变量 + 固定时长 + 资源需求 */
    public record Task(String startVar, long duration, long demand) {

        public Task {
            if (startVar == null || startVar.isBlank()) {
                throw new IllegalArgumentException("起始变量名不得为空");
            }
            if (duration <= 0 || demand <= 0) {
                throw new IllegalArgumentException("时长与资源需求须为正");
            }
        }
    }

    /** cumulative：任意时刻任务占用资源 ≤ 容量（必达段 time-table 检测） */
    public static PropagationEngine.Propagator cumulative(List<Task> tasks, long capacity) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("任务列表不得为空");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量须为正");
        }
        return domains -> {
            boolean changed = false;
            for (Task task : tasks) {
                requireVar(domains, task.startVar());
                if (task.demand() > capacity) {
                    throw new PropagationEngine.Infeasible(
                            "任务需求 " + task.demand() + " 超容量 " + capacity);
                }
            }
            // 事件时刻：各任务最早结束与最晚开始
            for (Task pivot : tasks) {
                long[] pd = domains.get(pivot.startVar());
                long mandStart = pd[1];
                long mandEnd = pd[0] + pivot.duration();
                if (mandEnd <= mandStart) {
                    continue;
                }
                for (Task other : tasks) {
                    if (other.equals(pivot)) {
                        continue;
                    }
                    long[] od = domains.get(other.startVar());
                    long oMandStart = od[1];
                    long oMandEnd = od[0] + other.duration();
                    if (oMandEnd <= oMandStart) {
                        continue;
                    }
                    long overlapStart = Math.max(mandStart, oMandStart);
                    long overlapEnd = Math.min(mandEnd, oMandEnd);
                    if (overlapStart < overlapEnd
                            && pivot.demand() + other.demand() > capacity) {
                        throw new PropagationEngine.Infeasible(
                                "cumulative 必达段重叠超容量：[" + overlapStart + "," + overlapEnd + "]");
                    }
                }
            }
            return changed;
        };
    }

    private static void requireVar(Map<String, long[]> domains, String var) {
        if (!domains.containsKey(var)) {
            throw new IllegalArgumentException("未注册变量：" + var);
        }
    }
}
