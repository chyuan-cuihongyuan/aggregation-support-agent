package cn.chyuan.ai.domain.querykernel.service;

import java.util.List;

/**
 * 表达式与求值（工单 0534 BL2，duckdb 表达式子集思想）。
 * 列引用/字面量/比较-逻辑-算术函数树/AND-OR 短路/NULL 三值逻辑
 * （比较未知、AND 见 false 即 false、OR 见 true 即 true）/非法类型与除零拒绝。
 */
public final class ExprEval {

    /** 表达式树：ColRef 列引用 / Lit 字面量 / Call 函数 */
    public sealed interface Expr permits ColRef, Lit, Call {
    }

    public record ColRef(int index, String name) implements Expr {
    }

    public record Lit(Object value) implements Expr {
    }

    /** op ∈ =,!=,<,<=,>,>=,AND,OR,NOT,+, -,*,/ ,ISNULL */
    public record Call(String op, List<Expr> args) implements Expr {
    }

    /** 对一行（列值列表）求值，返回值或 null（三值逻辑的 UNKNOWN） */
    public Object eval(Expr expr, List<Object> row) {
        if (expr instanceof ColRef col) {
            if (col.index() < 0 || col.index() >= row.size()) {
                throw new IllegalArgumentException("列越界: " + col.index());
            }
            return row.get(col.index());
        }
        if (expr instanceof Lit lit) {
            return lit.value();
        }
        Call call = (Call) expr;
        return switch (call.op()) {
            case "AND" -> and(call, row);
            case "OR" -> or(call, row);
            case "NOT" -> not(call, row);
            case "ISNULL" -> eval(call.args().get(0), row) == null;
            default -> scalar(call, row);
        };
    }

    private Boolean and(Call call, List<Object> row) {
        Boolean result = Boolean.TRUE;
        for (Expr arg : call.args()) {
            Object v = eval(arg, row);
            if (Boolean.FALSE.equals(v)) {
                return Boolean.FALSE;
            }
            if (v == null) {
                result = null;
            }
        }
        return result;
    }

    private Boolean or(Call call, List<Object> row) {
        Boolean result = Boolean.FALSE;
        for (Expr arg : call.args()) {
            Object v = eval(arg, row);
            if (Boolean.TRUE.equals(v)) {
                return Boolean.TRUE;
            }
            if (v == null) {
                result = null;
            }
        }
        return result;
    }

    private Boolean not(Call call, List<Object> row) {
        Object v = eval(call.args().get(0), row);
        if (v == null) {
            return null;
        }
        requireBoolean(v, "NOT");
        return !((Boolean) v);
    }

    private Object scalar(Call call, List<Object> row) {
        Object left = eval(call.args().get(0), row);
        switch (call.op()) {
            case "=":
            case "!=":
            case "<":
            case "<=":
            case ">":
            case ">=": {
                Object right = eval(call.args().get(1), row);
                if (left == null || right == null) {
                    return null;
                }
                int cmp = compare(left, right, call.op());
                return switch (call.op()) {
                    case "=" -> cmp == 0;
                    case "!=" -> cmp != 0;
                    case "<" -> cmp < 0;
                    case "<=" -> cmp <= 0;
                    case ">" -> cmp > 0;
                    default -> cmp >= 0;
                };
            }
            case "IS NULL":
                return left == null;
            default: {
                Object right = eval(call.args().get(1), row);
                if (left == null || right == null) {
                    return null;
                }
                requireNumber(left, call.op());
                requireNumber(right, call.op());
                double a = ((Number) left).doubleValue();
                double b = ((Number) right).doubleValue();
                return switch (call.op()) {
                    case "+" -> a + b;
                    case "-" -> a - b;
                    case "*" -> a * b;
                    case "/" -> {
                        if (b == 0.0d) {
                            throw new ArithmeticException("除零拒绝");
                        }
                        yield a / b;
                    }
                    default -> throw new IllegalArgumentException("非法算子: " + call.op());
                };
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compare(Object left, Object right, String op) {
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
        }
        if (left instanceof Boolean && right instanceof Boolean) {
            return Boolean.compare((Boolean) left, (Boolean) right);
        }
        if (left instanceof String && right instanceof String) {
            return ((Comparable) left).compareTo(right);
        }
        throw new IllegalArgumentException("类型不可比较（op " + op + "）");
    }

    private void requireNumber(Object v, String op) {
        if (!(v instanceof Number)) {
            throw new IllegalArgumentException("算术要求数值（op " + op + "）");
        }
    }

    private void requireBoolean(Object v, String op) {
        if (!(v instanceof Boolean)) {
            throw new IllegalArgumentException("逻辑要求布尔（op " + op + "）");
        }
    }
}
