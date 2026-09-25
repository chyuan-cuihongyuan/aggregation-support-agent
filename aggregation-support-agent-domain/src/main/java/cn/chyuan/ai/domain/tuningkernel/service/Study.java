package cn.chyuan.ai.domain.tuningkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Study 与 Trial（工单 0809 CR1/0614 CR6 部分口径，optuna 思想）。
 * study 方向 maximize·minimize/trial 生命周期 RUNNING·COMPLETE·PRUNED·FAIL/最佳值与参数追踪并列取先/预算与早停。
 */
public final class Study {

    public enum Direction { MAXIMIZE, MINIMIZE }

    public enum TrialState { RUNNING, COMPLETE, PRUNED, FAIL }

    public static final class Trial {
        public final int number;
        public final Map<String, Object> params;
        public TrialState state = TrialState.RUNNING;
        public Double value;
        public final List<Double> intermediates = new ArrayList<>();

        Trial(int number, Map<String, Object> params) {
            this.number = number;
            this.params = Map.copyOf(params);
        }
    }

    private final Direction direction;
    private final int maxTrials;
    private final int patience;
    private final List<Trial> trials = new ArrayList<>();
    private Trial best;
    private int noImprovement = 0;
    private boolean stopped = false;

    public Study(Direction direction, int maxTrials, int patience) {
        if (maxTrials <= 0 || patience <= 0) {
            throw new IllegalArgumentException("预算与耐心须为正");
        }
        this.direction = direction;
        this.maxTrials = maxTrials;
        this.patience = patience;
    }

    public Direction direction() {
        return direction;
    }

    public boolean stopped() {
        return stopped;
    }

    /** 建 trial：预算耗尽或早停拒绝 */
    public Trial ask(Map<String, Object> params) {
        if (stopped || trials.size() >= maxTrials) {
            throw new IllegalStateException("study 预算耗尽");
        }
        Trial trial = new Trial(trials.size(), params);
        trials.add(trial);
        return trial;
    }

    public void complete(Trial trial, double value) {
        ensureRunning(trial);
        trial.state = TrialState.COMPLETE;
        trial.value = value;
        if (best == null || better(value, best.value)) {
            best = trial;
            noImprovement = 0;
        } else {
            noImprovement++;
            if (noImprovement >= patience) {
                stopped = true;
            }
        }
    }

    public void prune(Trial trial) {
        ensureRunning(trial);
        trial.state = TrialState.PRUNED;
    }

    public void fail(Trial trial) {
        ensureRunning(trial);
        trial.state = TrialState.FAIL;
    }

    private void ensureRunning(Trial trial) {
        if (trial.state != TrialState.RUNNING) {
            throw new IllegalStateException("trial 已终态: #" + trial.number);
        }
    }

    /** 方向统一比较键：更优为 true（并列取先——严格更优才替换） */
    public boolean better(double candidate, Double incumbent) {
        if (incumbent == null) {
            return true;
        }
        return direction == Direction.MAXIMIZE ? candidate > incumbent : candidate < incumbent;
    }

    public List<Trial> trials() {
        return List.copyOf(trials);
    }

    public List<Trial> completed() {
        List<Trial> out = new ArrayList<>();
        for (Trial t : trials) {
            if (t.state == TrialState.COMPLETE) {
                out.add(t);
            }
        }
        return out;
    }

    /** 最佳 trial（并列取先到）；无完成 trial 抛出 */
    public Optional<Trial> best() {
        return Optional.ofNullable(best);
    }
}
