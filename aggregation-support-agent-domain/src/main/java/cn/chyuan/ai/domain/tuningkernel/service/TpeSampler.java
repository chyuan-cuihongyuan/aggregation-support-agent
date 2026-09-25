package cn.chyuan.ai.domain.tuningkernel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * TPE 采样器（工单 0812 CR4，optuna TPE 简化思想）。
 * 完成 trial 按方向排序取前 γ 为好组 l 其余坏组 g/候选加权 Parzen 频度（高斯核宽可配）/按 log l − log g 似然比择优/样本不足退随机。
 */
public final class TpeSampler implements Samplers.Sampler {

    private final Random random;
    private final int nStartup;
    private final double bandwidth;
    private final int candidates;

    public TpeSampler(long seed, int nStartup, double bandwidth, int candidates) {
        this.random = new Random(seed);
        this.nStartup = nStartup;
        this.bandwidth = bandwidth;
        this.candidates = candidates;
    }

    public TpeSampler(long seed) {
        this(seed, 5, 1.0, 24);
    }

    @Override
    public Map<String, Object> sample(Space space, Study study) {
        List<Study.Trial> done = study.completed();
        if (done.size() < nStartup) {
            return space.sample(random);
        }
        List<Study.Trial> sorted = new ArrayList<>(done);
        sorted.sort(Comparator.comparingDouble((Study.Trial t) -> t.value).reversed());
        if (study.direction() == Study.Direction.MINIMIZE) {
            sorted.sort(Comparator.comparingDouble(t -> t.value));
        }
        int gamma = Math.max(1, sorted.size() / 3);
        List<Study.Trial> good = sorted.subList(0, gamma);
        List<Study.Trial> bad = sorted.subList(gamma, sorted.size());
        Map<String, Object> bestCandidate = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int c = 0; c < candidates; c++) {
            Map<String, Object> candidate = space.sample(random);
            double score = Math.log(density(candidate, space, good) + 1e-12)
                    - Math.log(density(candidate, space, bad) + 1e-12);
            if (score > bestScore) {
                bestScore = score;
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    /** 加权 Parzen 频度：数值高斯核 / categorical 命中率，逐参数乘积 */
    private double density(Map<String, Object> candidate, Space space, List<Study.Trial> group) {
        if (group.isEmpty()) {
            return 1e-12;
        }
        double product = 1.0;
        for (String name : space.names()) {
            Space.Param param = space.param(name);
            Object v = candidate.get(name);
            product *= paramDensity(param, v, group);
        }
        return product;
    }

    private double paramDensity(Space.Param param, Object value, List<Study.Trial> group) {
        switch (param) {
            case Space.IntP i -> {
                double x = ((Long) value).doubleValue();
                return gaussian(x, group.stream().map(t -> ((Long) t.params.get(i.name())).doubleValue()).toList());
            }
            case Space.FloatP f -> {
                double x = ((Double) value);
                return gaussian(x, group.stream().map(t -> ((Double) t.params.get(f.name()))).toList());
            }
            case Space.CatP c -> {
                long hits = group.stream().filter(t -> value.equals(t.params.get(c.name()))).count();
                return (hits + 0.5) / (group.size() + 1.0);
            }
        }
    }

    private double gaussian(double x, List<Double> points) {
        if (points.isEmpty()) {
            return 1e-12;
        }
        double sum = 0.0;
        for (double p : points) {
            double d = (x - p) / bandwidth;
            sum += Math.exp(-0.5 * d * d);
        }
        return sum / points.size();
    }
}
