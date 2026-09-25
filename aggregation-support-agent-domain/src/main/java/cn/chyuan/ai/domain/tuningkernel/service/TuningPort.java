package cn.chyuan.ai.domain.tuningkernel.service;

import java.util.List;
import java.util.Map;

/**
 * 调优端口（工单 0814/0815/0816 CR6·CR7·CR8，optuna 思想）。
 * create/ask/tell/最佳参数入口统一编排/目标方向与预算早停由 Study 承载/inferkernel 采样统计序列作目标值只读联动
 * （seriesObjective 泛型序列不 import）/tuning-kernel.enabled 默认关（开启才改变行为）。
 */
public interface TuningPort {

    /** 中间值上报：返回 false 表示已被剪枝，目标函数应尽快返回 */
    interface Reporter {
        boolean report(double intermediate);
    }

    interface Objective {
        double evaluate(Map<String, Object> params, Reporter reporter);
    }

    record OptimizeResult(int trialsRun, int pruned, Study.Trial best) {
    }

    /** 建study */
    Study createStudy(Study.Direction direction, int maxTrials, int patience);

    /** 优化循环：采样→ask→中间值上报（剪枝）→tell */
    OptimizeResult optimize(Study study, Space space, Samplers.Sampler sampler, Objective objective,
                            int steps, Pruners.Pruner pruner);

    /** inferkernel 只读联动形态：采样统计序列均值作 trial 目标值（形状数据不 import inferkernel） */
    static double seriesObjective(List<? extends Number> series) {
        if (series.isEmpty()) {
            throw new IllegalArgumentException("空统计序列");
        }
        double sum = 0;
        for (Number n : series) {
            sum += n.doubleValue();
        }
        return sum / series.size();
    }

    static TuningPort inMemory() {
        return new InMemoryTuning();
    }
}

final class InMemoryTuning implements TuningPort {

    @Override
    public Study createStudy(Study.Direction direction, int maxTrials, int patience) {
        return new Study(direction, maxTrials, patience);
    }

    @Override
    public TuningPort.OptimizeResult optimize(Study study, Space space, Samplers.Sampler sampler,
                                              TuningPort.Objective objective, int steps, Pruners.Pruner pruner) {
        int run = 0;
        int pruned = 0;
        while (!study.stopped()) {
            Study.Trial trial;
            Map<String, Object> params;
            try {
                params = sampler.sample(space, study);
                trial = study.ask(params);
            } catch (IllegalStateException exhausted) {
                break;
            }
            run++;
            int[] step = {0};
            boolean[] prunedFlag = {false};
            double value = objective.evaluate(params, intermediate -> {
                step[0]++;
                if (pruner != null && pruner.prune(study, trial, intermediate, step[0])) {
                    prunedFlag[0] = true;
                    return false;
                }
                trial.intermediates.add(intermediate);
                return true;
            });
            if (prunedFlag[0]) {
                study.prune(trial);
                pruned++;
            } else {
                study.complete(trial, value);
            }
        }
        return new TuningPort.OptimizeResult(run, pruned, study.best().orElse(null));
    }
}
