package cn.chyuan.ai.domain.validkernel.service;

/**
 * 字段类型（工单 1159 FA1，pydantic 思想）。
 * string/int/bool/number/list/object 声明与值匹配；未知类型声明拒绝；
 * list 支持元素类型语法 list:&lt;elem&gt;。
 */
public final class FieldTypes {

    public static final java.util.Set<String> PRIMITIVES = java.util.Set.of("string", "int", "bool", "number");

    private FieldTypes() {
    }

    /** 值的实际类别名 */
    public static String kindOf(Object value) {
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Integer || value instanceof Long) {
            return "int";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof Double || value instanceof Float) {
            return "number";
        }
        if (value instanceof java.util.List) {
            return "list";
        }
        if (value instanceof java.util.Map) {
            return "object";
        }
        throw new IllegalArgumentException("不支持的值类型: " + value.getClass().getName());
    }

    /** 声明类型是否合法（list:&lt;elem&gt; 校验元素类型） */
    public static boolean validDeclaration(String type) {
        if (PRIMITIVES.contains(type) || type.equals("list") || type.equals("object")) {
            return true;
        }
        return type.startsWith("list:") && PRIMITIVES.contains(type.substring(5));
    }

    /** 值是否匹配声明类型 */
    public static boolean matches(Object value, String type) {
        if (type.startsWith("list:")) {
            return value instanceof java.util.List;
        }
        return switch (type) {
            case "string" -> value instanceof String;
            case "int" -> value instanceof Integer || value instanceof Long;
            case "bool" -> value instanceof Boolean;
            case "number" -> value instanceof Number;
            case "list" -> value instanceof java.util.List;
            case "object" -> value instanceof java.util.Map;
            default -> throw new IllegalArgumentException("未知类型声明: " + type);
        };
    }

    /** list:&lt;elem&gt; 的元素类型；非列表声明返回 null */
    public static String elementType(String type) {
        return type.startsWith("list:") ? type.substring(5) : null;
    }
}
