package cn.chyuan.ai.domain.templatekernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 转义与安全字符串（工单 0631 BW6，jinja2 autoescape 思想）。
 * HTML 五实体转义（& < > " '）默认开可配置关/
 * 安全字符串 SafeString 标记免转义（过滤器产物标记）/
 * 非字符串值字符串化（数字/布尔/null 空串）。
 */
public final class Escaping {

    /** 免转义安全串（过滤器产物标记） */
    public record SafeString(String value) {
    }

    private final boolean enabled;

    public Escaping(boolean enabled) {
        this.enabled = enabled;
    }

    /** 输出规则：SafeString 直出，其余按配置转义 */
    public String output(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof SafeString safe) {
            return safe.value();
        }
        String text = stringify(value);
        return enabled ? escape(text) : text;
    }

    public boolean enabled() {
        return enabled;
    }

    /** HTML 五实体转义 */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            switch (text.charAt(i)) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(text.charAt(i));
            }
        }
        return sb.toString();
    }

    /** 非字符串值字符串化 */
    public static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value);
    }
}
