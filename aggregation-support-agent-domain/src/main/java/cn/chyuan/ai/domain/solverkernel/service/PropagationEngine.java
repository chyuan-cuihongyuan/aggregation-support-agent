package cn.chyuan.ai.domain.solverkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 变量域与传播器（工单 0596 BS3，CP 区间传播思想）。
 * 整数区间域（lb/ub）/约束到传播器注册（线性约束区间化传播）/
 * 定点迭代直至无收缩/域空即不可行剪枝/传播轮次计数。
 */
public final class PropagationEngine {

    /** 不可行（域空或约束冲突） */
    public static final class Infeasible extends RuntimeException {
        public Infeasible(String message) {
            super(message);
        }
    }

    /** 传播器：对域做单调收缩；返回是否有收缩；不可行抛 Infeasible */
    public interface Propagator {
        boolean propagate(Map<String, long[]> domains) throws Infeasible;
    }

    /** 线性约束传播器（区间化：min/max 上下界收紧） */
    public static Propagator linear(LinearExpr.Constraint constraint) {
        return domains -> propagateLinear(constraint, domains);
    }

    private final List<Propagator> propagators = new ArrayList<>();
    private long propagationRounds;

    /** 注册传播器 */
    public synchronized void add(Propagator propagator) {
        if (propagator == null) {
            throw new IllegalArgumentException("传播器不得为 null");
        }
        propagators.add(propagator);
    }

    /** 定点传播：迭代所有传播器直至无收缩；返回收缩总轮次 */
    public synchronized long fixpoint(Map<String, long[]> domains) throws Infeasible {
        long rounds = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Propagator propagator : propagators) {
                if (propagator.propagate(domains)) {
                    changed = true;
                }
            }
            rounds++;
            if (rounds > 10_000) {
                throw new IllegalStateException("传播定点迭代异常：疑似非单调传播器");
            }
        }
        propagationRounds += rounds;
        return rounds;
    }

    /** 累计传播轮次（求解统计口径） */
    public synchronized long totalRounds() {
        return propagationRounds;
    }

    /** 线性约束区间传播：minS/maxS 一致性 + 每变量上下界收紧 */
    static boolean propagateLinear(LinearExpr.Constraint c, Map<String, long[]> domains) throws Infeasible {
        boolean changed = false;
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            long minS = c.constant();
            long maxS = c.constant();
            for (Map.Entry<String, Long> t : c.terms().entrySet()) {
                long[] d = domains.get(t.getKey());
                if (d == null) {
                    throw new IllegalArgumentException("未注册变量：" + t.getKey());
                }
                long coeff = t.getValue();
                if (coeff >= 0) {
                    minS += coeff * d[0];
                    maxS += coeff * d[1];
                } else {
                    minS += coeff * d[1];
                    maxS += coeff * d[0];
                }
            }
            switch (c.op()) {
                case LE -> {
                    if (minS > 0) {
                        throw new Infeasible("约束不可行：最小值 " + minS + " > 0");
                    }
                }
                case GE -> {
                    if (maxS < 0) {
                        throw new Infeasible("约束不可行：最大值 " + maxS + " < 0");
                    }
                }
                case EQ -> {
                    if (minS > 0 || maxS < 0) {
                        throw new Infeasible("约束不可行：0 不在 [" + minS + "," + maxS + "]");
                    }
                }
            }
            for (Map.Entry<String, Long> t : c.terms().entrySet()) {
                long coeff = t.getValue();
                long[] d = domains.get(t.getKey());
                long minOthers = minS;
                long maxOthers = maxS;
                if (coeff >= 0) {
                    minOthers -= coeff * d[0];
                    maxOthers -= coeff * d[1];
                } else {
                    minOthers -= coeff * d[1];
                    maxOthers -= coeff * d[0];
                }
                long newLb = d[0];
                long newUb = d[1];
                switch (c.op()) {
                    case LE -> {
                        // coeff * x <= -minOthers
                        long bound = -minOthers;
                        if (coeff > 0) {
                            newUb = Math.min(newUb, floorDiv(bound, coeff));
                        } else if (coeff < 0) {
                            newLb = Math.max(newLb, ceilDiv(bound, coeff));
                        }
                    }
                    case GE -> {
                        // coeff * x >= -maxOthers
                        long bound = -maxOthers;
                        if (coeff > 0) {
                            newLb = Math.max(newLb, ceilDiv(bound, coeff));
                        } else if (coeff < 0) {
                            newUb = Math.min(newUb, floorDiv(bound, coeff));
                        }
                    }
                    case EQ -> {
                        // coeff * x == -minOthers .. -maxOthers（区间内任取）
                        long lo = Math.min(-minOthers, -maxOthers);
                        long hi = Math.max(-minOthers, -maxOthers);
                        if (coeff > 0) {
                            newLb = Math.max(newLb, ceilDiv(lo, coeff));
                            newUb = Math.min(newUb, floorDiv(hi, coeff));
                        } else if (coeff < 0) {
                            newLb = Math.max(newLb, ceilDiv(hi, coeff));
                            newUb = Math.min(newUb, floorDiv(lo, coeff));
                        }
                    }
                }
                if (newLb > d[0] || newUb < d[1]) {
                    if (newLb > newUb) {
                        throw new Infeasible("域空：" + t.getKey() + " ∈ [" + newLb + "," + newUb + "]");
                    }
                    d[0] = newLb;
                    d[1] = newUb;
                    changed = true;
                    progressed = true;
                }
            }
        }
        return changed;
    }

    /** 域快照（深拷贝） */
    public static Map<String, long[]> snapshot(Map<String, long[]> domains) {
        Map<String, long[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : domains.entrySet()) {
            copy.put(e.getKey(), new long[]{e.getValue()[0], e.getValue()[1]});
        }
        return copy;
    }

    /** 域是否全部固定 */
    public static boolean allFixed(Map<String, long[]> domains) {
        for (long[] d : domains.values()) {
            if (d[0] != d[1]) {
                return false;
            }
        }
        return true;
    }

    /** 最小域变量选择（tie 按注册序，确定性） */
    public static String selectBranchVar(Map<String, long[]> domains) {
        String best = null;
        long bestSize = Long.MAX_VALUE;
        for (Map.Entry<String, long[]> e : domains.entrySet()) {
            long size = e.getValue()[1] - e.getValue()[0];
            if (size > 0 && size < bestSize) {
                bestSize = size;
                best = e.getKey();
            }
        }
        return best;
    }

    static long floorDiv(long a, long b) {
        return Math.floorDiv(a, b);
    }

    static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }
}
