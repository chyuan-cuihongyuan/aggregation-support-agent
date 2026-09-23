package cn.chyuan.ai.domain.templatekernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置过滤器（工单 0627 BW2 过滤器面）。
 * upper/lower/length/default/join/round/trim/first/sort/safe——
 * safe 产物标记 SafeString 免转义（BW6 联动面）。
 */
public final class Filters {

    private Filters() {
    }

    /** 内置过滤器表 */
    public static Map<String, Expressions.Filter> builtins() {
        Map<String, Expressions.Filter> out = new LinkedHashMap<>();
        out.put("upper", (v, a) -> String.valueOf(v).toUpperCase());
        out.put("lower", (v, a) -> String.valueOf(v).toLowerCase());
        out.put("length", (v, a) -> v == null ? 0 : sizeOf(v));
        out.put("default", (v, a) -> v == null || String.valueOf(v).isEmpty()
                ? (a.isEmpty() ? "" : a.get(0)) : v);
        out.put("join", (v, a) -> {
            String sep = a.isEmpty() ? "" : String.valueOf(a.get(0));
            List<String> parts = new ArrayList<>();
            if (v instanceof Iterable<?> it) {
                for (Object o : it) {
                    parts.add(Escaping.stringify(o));
                }
            } else if (v != null) {
                parts.add(Escaping.stringify(v));
            }
            return String.join(sep, parts);
        });
        out.put("round", (v, a) -> {
            int digits = a.isEmpty() ? 0 : Integer.parseInt(String.valueOf(a.get(0)));
            double value = Double.parseDouble(String.valueOf(v));
            double factor = Math.pow(10, digits);
            return Math.round(value * factor) / factor;
        });
        out.put("trim", (v, a) -> String.valueOf(v).trim());
        out.put("first", (v, a) -> {
            if (v instanceof List<?> list && !list.isEmpty()) {
                return list.get(0);
            }
            if (v instanceof String s && !s.isEmpty()) {
                return s.substring(0, 1);
            }
            return null;
        });
        out.put("sort", (v, a) -> {
            List<String> parts = new ArrayList<>();
            if (v instanceof Iterable<?> it) {
                for (Object o : it) {
                    parts.add(Escaping.stringify(o));
                }
            }
            java.util.Collections.sort(parts);
            return parts;
        });
        out.put("safe", (v, a) -> new Escaping.SafeString(Escaping.stringify(v)));
        return out;
    }

    private static int sizeOf(Object value) {
        if (value instanceof String s) {
            return s.length();
        }
        if (value instanceof Map<?, ?> m) {
            return m.size();
        }
        if (value instanceof Iterable<?> it) {
            int n = 0;
            for (Object ignored : it) {
                n++;
            }
            return n;
        }
        return 1;
    }
}
