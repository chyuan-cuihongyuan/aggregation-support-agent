package cn.chyuan.ai.domain.solverkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 整数线性表达式与约束（工单 0594 BS1，OR-Tools CP-SAT 线性约束思想）。
 * 变量系数线性组合+常量/==、<=、>= 归一化为区间约束/
 * 同变量项合并/系数为零折叠/未注册变量拒绝。
 */
public final class LinearExpr {

    /** 线性项（变量×系数） */
    public record Term(String var, long coeff) {

        public Term {
            if (var == null || var.isBlank()) {
                throw new IllegalArgumentException("变量名不得为空");
            }
        }
    }

    /** 比较算子 */
    public enum Op {
        EQ, LE, GE
    }

    /** 归一化线性约束：sum(coeff_i * var_i) + constant OP 0 */
    public record Constraint(Map<String, Long> terms, long constant, Op op) {

        public Constraint {
            terms = Map.copyOf(terms);
        }
    }

    private final Map<String, Long> terms;
    private final long constant;

    private LinearExpr(Map<String, Long> terms, long constant) {
        this.terms = terms;
        this.constant = constant;
    }

    /** 构建表达式（常量起步） */
    public static LinearExpr of(long constant, Term... terms) {
        Map<String, Long> merged = new LinkedHashMap<>();
        for (Term term : terms == null ? new Term[0] : terms) {
            merged.merge(term.var(), term.coeff(), Long::sum);
        }
        merged.values().removeIf(v -> v == 0L);
        return new LinearExpr(merged, constant);
    }

    /** 表达式相加（同变量合并） */
    public LinearExpr plus(LinearExpr other) {
        if (other == null) {
            throw new IllegalArgumentException("相加表达式不得为 null");
        }
        Map<String, Long> merged = new LinkedHashMap<>(terms);
        for (Map.Entry<String, Long> e : other.terms.entrySet()) {
            merged.merge(e.getKey(), e.getValue(), Long::sum);
        }
        merged.values().removeIf(v -> v == 0L);
        return new LinearExpr(merged, constant + other.constant);
    }

    /** 表达式数乘 */
    public LinearExpr scale(long factor) {
        Map<String, Long> scaled = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : terms.entrySet()) {
            scaled.put(e.getKey(), e.getValue() * factor);
        }
        return new LinearExpr(scaled, constant * factor);
    }

    /** 约束：this OP rhs（归一为 sum + (constant - rhs) OP 0） */
    public Constraint eq(long rhs) {
        return new Constraint(terms, constant - rhs, Op.EQ);
    }

    public Constraint le(long rhs) {
        return new Constraint(terms, constant - rhs, Op.LE);
    }

    public Constraint ge(long rhs) {
        return new Constraint(terms, constant - rhs, Op.GE);
    }

    /** 项（合并零系数折叠后） */
    public Map<String, Long> terms() {
        return Map.copyOf(terms);
    }

    /** 常量 */
    public long constant() {
        return constant;
    }

    /** 变量数 */
    public int variableCount() {
        return terms.size();
    }
}
