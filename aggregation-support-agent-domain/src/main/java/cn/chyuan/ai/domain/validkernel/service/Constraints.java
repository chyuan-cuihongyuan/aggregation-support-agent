package cn.chyuan.ai.domain.validkernel.service;

import java.util.List;

/**
 * 字段约束（工单 1161 FA3，pydantic 思想）。
 * min/max 数值区间、len 字符串长度区间、pattern 正则、enum 枚举取值；
 * 违反约束以字段路径报出。表达式口径：min=3/max=9/len=2:16/pattern=^a/enum=a|b|c。
 */
public record Constraints(Double min, Double max, Integer minLength, Integer maxLength,
                          String pattern, List<String> allowed) {

    public static Constraints none() {
        return new Constraints(null, null, null, null, null, null);
    }

    public boolean empty() {
        return min == null && max == null && minLength == null && maxLength == null
                && pattern == null && allowed == null;
    }

    /** 解析约束表达式：kind=expr */
    public static Constraints parse(String kind, String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("约束表达式不能为空");
        }
        return switch (kind) {
            case "min" -> new Constraints(Double.valueOf(expr), null, null, null, null, null);
            case "max" -> new Constraints(null, Double.valueOf(expr), null, null, null, null);
            case "len" -> {
                String[] parts = expr.split(":", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("len 表达式须为 min:max: " + expr);
                }
                yield new Constraints(null, null, Integer.valueOf(parts[0]), Integer.valueOf(parts[1]), null, null);
            }
            case "pattern" -> new Constraints(null, null, null, null, expr, null);
            case "enum" -> new Constraints(null, null, null, null, null, List.of(expr.split("\\|")));
            default -> throw new IllegalArgumentException("未知约束种类: " + kind);
        };
    }

    /** 校验：违反约束抛 ISE 并报字段路径 */
    public void check(String path, Object value) {
        if (min != null && ((Number) value).doubleValue() < min) {
            throw new IllegalStateException(path + " 低于下限 " + min);
        }
        if (max != null && ((Number) value).doubleValue() > max) {
            throw new IllegalStateException(path + " 超过上限 " + max);
        }
        if (minLength != null && value.toString().length() < minLength) {
            throw new IllegalStateException(path + " 长度不足 " + minLength);
        }
        if (maxLength != null && value.toString().length() > maxLength) {
            throw new IllegalStateException(path + " 长度超出 " + maxLength);
        }
        if (pattern != null && !java.util.regex.Pattern.compile(pattern).matcher(value.toString()).find()) {
            throw new IllegalStateException(path + " 不匹配模式 " + pattern);
        }
        if (allowed != null && !allowed.contains(value.toString())) {
            throw new IllegalStateException(path + " 不在枚举取值内");
        }
    }
}
