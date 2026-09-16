package cn.chyuan.ai.domain.scankernel.service;

import java.util.Optional;

/**
 * metavariable 绑定与比较（工单 0453 BC3）。
 * 绑定一致性校验（同名须同绑定）/ {@code $X == $X} 形状恒等比较（同绑定恒真、异绑定恒假）
 * / 数值字面量比较子集。纯函数。
 */
public class MetavarBinding {

    /** 绑定冲突：返回错误信息，一致返回空 */
    public Optional<String> conflict(MapBinding bindings, String name, String token) {
        String previous = bindings.get(name);
        if (previous != null && !previous.equals(token)) {
            return Optional.of("metavariable $" + name + " 绑定冲突: " + previous + " vs " + token);
        }
        return Optional.empty();
    }

    /**
     * 比较子集：左/右为绑定值或数字字面量。
     * {@code $X == $X}：同绑定恒真、异绑定恒假；数字按数值比较。
     */
    public Boolean compare(String left, String op, String right) {
        boolean numeric = left.matches("-?\\d+(\\.\\d+)?") && right.matches("-?\\d+(\\.\\d+)?");
        return switch (op) {
            case "==" -> numeric ? Double.parseDouble(left) == Double.parseDouble(right) : left.equals(right);
            case "!=" -> numeric ? Double.parseDouble(left) != Double.parseDouble(right) : !left.equals(right);
            case "<" -> requireNumeric(numeric) && Double.parseDouble(left) < Double.parseDouble(right);
            case "<=" -> requireNumeric(numeric) && Double.parseDouble(left) <= Double.parseDouble(right);
            case ">" -> requireNumeric(numeric) && Double.parseDouble(left) > Double.parseDouble(right);
            case ">=" -> requireNumeric(numeric) && Double.parseDouble(left) >= Double.parseDouble(right);
            default -> throw new IllegalArgumentException("未知比较操作: " + op);
        };
    }

    private static boolean requireNumeric(boolean numeric) {
        if (!numeric) {
            throw new IllegalArgumentException("排序比较仅支持数值");
        }
        return true;
    }

    /** 简单绑定环境 */
    public static class MapBinding {

        private final java.util.Map<String, String> values = new java.util.HashMap<>();

        public void put(String name, String token) {
            values.put(name, token);
        }

        public String get(String name) {
            return values.get(name);
        }
    }
}
