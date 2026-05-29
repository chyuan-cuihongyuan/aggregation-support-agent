package cn.chyuan.ai.types.common;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 敏感数据脱敏工具 — 日志输出前过滤密码、Token、API Key 等敏感字段
 */
public class SensitiveDataMasker {

    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "token", "authorization", "auth-key", "apikey", "apisecret",
            "jwt", "cookie", "set-cookie", "dbpassword", "secret", "private_key",
            "access_key", "secret_key", "credentials"
    );

    private static final String FIELD_PATTERN_STR = SENSITIVE_FIELDS.stream()
            .map(Pattern::quote)
            .collect(Collectors.joining("|"));

    private static final Pattern JSON_FIELD_PATTERN = Pattern.compile(
            "\"(" + FIELD_PATTERN_STR + ")\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "(" + FIELD_PATTERN_STR + ")\\s*[:=]\\s*(\\S+)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 对 JSON 字符串中的敏感字段值进行脱敏
     */
    public static String maskJson(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        Matcher matcher = JSON_FIELD_PATTERN.matcher(input);
        return matcher.replaceAll("\"$1\":\"****\"");
    }

    /**
     * 对键值对格式（如 HTTP Header）中的敏感值进行脱敏
     */
    public static String maskHeaders(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        Matcher matcher = HEADER_PATTERN.matcher(input);
        return matcher.replaceAll("$1=****");
    }

    /**
     * 对任意字符串进行脱敏（同时处理 JSON 和键值对格式）
     */
    public static String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return maskHeaders(maskJson(input));
    }
}
