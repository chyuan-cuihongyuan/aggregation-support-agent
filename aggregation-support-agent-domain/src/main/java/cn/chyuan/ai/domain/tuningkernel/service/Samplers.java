package cn.chyuan.ai.domain.tuningkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 采样器（工单 0811 CR3，optuna 思想）。
 * 均匀随机（种子确定性）/网格全排列枚举与耗尽语义。
 */
public final class Samplers {

    /** 采样器接口：TPE 与随机/网格同型 */
    public interface Sampler {
        Map<String, Object> sample(Space space, Study study);
    }

    public static final class RandomSampler implements Sampler {
        private final Random random;

        public RandomSampler(long seed) {
            this.random = new Random(seed);
        }

        @Override
        public Map<String, Object> sample(Space space, Study study) {
            return space.sample(random);
        }
    }

    /** 网格采样器：全排列按序枚举（末位先变）；耗尽抛出 */
    public static final class GridSampler implements Sampler {
        private final List<Map<String, Object>> grid = new ArrayList<>();
        private int cursor = 0;
        private boolean built = false;

        private void build(Space space) {
            grid.clear();
            grid.add(new LinkedHashMap<>());
            for (String name : space.names()) {
                List<Object> values = choicesOf(space.param(name));
                List<Map<String, Object>> next = new ArrayList<>();
                for (Map<String, Object> row : grid) {
                    for (Object v : values) {
                        Map<String, Object> copy = new LinkedHashMap<>(row);
                        copy.put(name, v);
                        next.add(copy);
                    }
                }
                grid.clear();
                grid.addAll(next);
            }
            if (grid.isEmpty()) {
                throw new IllegalArgumentException("空空间无网格");
            }
            built = true;
        }

        private static List<Object> choicesOf(Space.Param p) {
            List<Object> out = new ArrayList<>();
            switch (p) {
                case Space.IntP i -> {
                    if (i.log()) {
                        for (long v = i.low(); v <= i.high(); v *= 2) {
                            out.add(v);
                            if (v > i.high() / 2) {
                                break;
                            }
                        }
                    } else {
                        for (long v = i.low(); v <= i.high(); v++) {
                            out.add(v);
                        }
                    }
                }
                case Space.FloatP f -> {
                    out.add(f.low());
                    out.add(f.high());
                }
                case Space.CatP c -> out.addAll(c.choices());
            }
            return out;
        }

        @Override
        public Map<String, Object> sample(Space space, Study study) {
            if (!built) {
                build(space);
            }
            if (cursor >= grid.size()) {
                throw new IllegalStateException("网格已耗尽: " + grid.size());
            }
            return grid.get(cursor++);
        }

        public int remaining() {
            return grid.size() - cursor;
        }
    }

    private Samplers() {
    }
}
