package cn.chyuan.ai.domain.tuningkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 剪枝器（工单 0813 CR5，optuna 思想）。
 * 中位数规则：同步序中间值劣于已完成中位即剪（无历史不剪）；逐次减半：里程碑处与记录阈值比较，更优晋级记账。
 */
public final class Pruners {

    public interface Pruner {
        boolean prune(Study study, Study.Trial trial, double intermediate, int step);
    }

    /** 中位数剪枝 */
    public static final class MedianPruner implements Pruner {
        @Override
        public boolean prune(Study study, Study.Trial trial, double intermediate, int step) {
            List<Double> peers = new ArrayList<>();
            for (Study.Trial done : study.completed()) {
                if (done.intermediates.size() >= step) {
                    peers.add(done.intermediates.get(step - 1));
                }
            }
            if (peers.isEmpty()) {
                return false;
            }
            peers.sort(Double::compareTo);
            double median = peers.get(peers.size() / 2);
            return !study.better(intermediate, median);
        }
    }

    /** 逐次减半：里程碑 minRung·eta^k 处比较历史记录阈值，更优晋级并刷新阈值，否则剪 */
    public static final class SuccessiveHalvingPruner implements Pruner {
        private final int eta;
        private final int minRung;
        private final Map<Integer, Double> rungThreshold = new HashMap<>();

        public SuccessiveHalvingPruner(int eta, int minRung) {
            if (eta < 2 || minRung < 1) {
                throw new IllegalArgumentException("eta≥2 且 minRung≥1");
            }
            this.eta = eta;
            this.minRung = minRung;
        }

        private boolean isMilestone(int step) {
            if (step < minRung || step % minRung != 0) {
                return false;
            }
            int v = step / minRung;
            return (v & (v - 1)) == 0;
        }

        @Override
        public boolean prune(Study study, Study.Trial trial, double intermediate, int step) {
            if (!isMilestone(step)) {
                return false;
            }
            Double threshold = rungThreshold.get(step);
            boolean promote = threshold == null || study.better(intermediate, threshold);
            if (!promote) {
                return true;
            }
            rungThreshold.put(step, threshold == null ? intermediate
                    : (study.better(intermediate, threshold) ? intermediate : threshold));
            return false;
        }

        public Map<Integer, Double> rungs() {
            return Map.copyOf(rungThreshold);
        }
    }

    private Pruners() {
    }
}
