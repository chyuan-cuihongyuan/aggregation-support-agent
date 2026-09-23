package cn.chyuan.ai.domain.solverkernel.service;

import java.util.List;

/**
 * 布尔子句与 CNF（工单 0595 BS2，CP-SAT 布尔面思想）。
 * 正负文字/子句构造/单元子句传播（赋值联动）/空子句矛盾检测/
 * 重言子句（x∨¬x）吸收/布尔变量建模为 0-1 整数域。
 */
public final class BoolClauses {

    /** 文字：变量（0-1 域）+ 正负 */
    public record Literal(String var, boolean negated) {

        public Literal {
            if (var == null || var.isBlank()) {
                throw new IllegalArgumentException("文字变量名不得为空");
            }
        }

        public long valueWhenTrue() {
            return negated ? 0L : 1L;
        }

        public long valueWhenFalse() {
            return negated ? 1L : 0L;
        }
    }

    /** 子句：文字析取 */
    public record Clause(List<Literal> literals) {

        public Clause {
            literals = List.copyOf(literals);
            if (literals.isEmpty()) {
                throw new IllegalArgumentException("空子句恒假：建模错误");
            }
        }

        /** 单元子句 */
        public boolean unit() {
            return literals.size() == 1;
        }

        /** 重言子句（含 x 与 ¬x） */
        public boolean tautology() {
            for (Literal a : literals) {
                for (Literal b : literals) {
                    if (a.var().equals(b.var()) && a.negated() != b.negated()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public static Literal pos(String var) {
        return new Literal(var, false);
    }

    public static Literal neg(String var) {
        return new Literal(var, true);
    }

    /** 子句构造 */
    public static Clause clause(Literal... literals) {
        return new Clause(List.of(literals));
    }

    /** 子句传播器：全假检测 + 单元文字强制真 */
    public static PropagationEngine.Propagator propagator(Clause clause) {
        if (clause.tautology()) {
            return domains -> false;
        }
        return domains -> {
            boolean changed = false;
            boolean progressed = true;
            while (progressed) {
                progressed = false;
                int unassigned = 0;
                Literal open = null;
                for (Literal lit : clause.literals()) {
                    long[] d = domains.get(lit.var());
                    if (d == null) {
                        throw new IllegalArgumentException("未注册布尔变量：" + lit.var());
                    }
                    if (d[0] == d[1]) {
                        if (d[0] == lit.valueWhenTrue()) {
                            return changed;
                        }
                    } else {
                        unassigned++;
                        open = lit;
                    }
                }
                if (unassigned == 0) {
                    throw new PropagationEngine.Infeasible("子句全假：矛盾");
                }
                if (unassigned == 1 && open != null) {
                    long[] d = domains.get(open.var());
                    long v = open.valueWhenTrue();
                    d[0] = v;
                    d[1] = v;
                    changed = true;
                    progressed = true;
                }
            }
            return changed;
        };
    }
}
