package cn.chyuan.ai.domain.validkernel.service;

/**
 * 类型强转（工单 1160 FA2，pydantic 思想）。
 * 宽松模式：字符串数字→int/number、字符串 true/false 与 0/1→bool；
 * 严格模式：任何非精确匹配类型拒绝强转；列表元素按同规则递归。
 */
public final class Coercions {

    private Coercions() {
    }

    /** 按模式强转标量；不匹配且无法强转抛 IAE */
    public static Object coerce(Object value, String type, boolean strict) {
        if (value == null) {
            return null;
        }
        if (FieldTypes.matches(value, type)) {
            return value;
        }
        if (strict) {
            throw new IllegalArgumentException("严格模式拒绝强转: " + FieldTypes.kindOf(value) + " -> " + type);
        }
        return lax(value, type);
    }

    /** 列表元素递归强转：同标量规则逐元素作用 */
    public static java.util.List<Object> coerceList(java.util.List<?> values, String elementType, boolean strict) {
        java.util.List<Object> coerced = new java.util.ArrayList<>();
        for (Object element : values) {
            coerced.add(coerce(element, elementType, strict));
        }
        return coerced;
    }

    private static Object lax(Object value, String type) {
        switch (type) {
            case "int" -> {
                if (value instanceof String text) {
                    try {
                        return Integer.valueOf(text.trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("无法强转为 int: " + text);
                    }
                }
            }
            case "number" -> {
                if (value instanceof String text) {
                    try {
                        return Double.valueOf(text.trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("无法强转为 number: " + text);
                    }
                }
            }
            case "bool" -> {
                if (value instanceof String text) {
                    if (text.equalsIgnoreCase("true")) {
                        return Boolean.TRUE;
                    }
                    if (text.equalsIgnoreCase("false")) {
                        return Boolean.FALSE;
                    }
                }
                if (value instanceof Integer flag) {
                    if (flag == 0) {
                        return Boolean.FALSE;
                    }
                    if (flag == 1) {
                        return Boolean.TRUE;
                    }
                }
            }
            default -> {
                // string/list/object 不做强转
            }
        }
        throw new IllegalArgumentException("宽松模式无法强转: " + FieldTypes.kindOf(value) + " -> " + type);
    }
}
