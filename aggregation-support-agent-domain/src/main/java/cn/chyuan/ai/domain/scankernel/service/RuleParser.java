package cn.chyuan.ai.domain.scankernel.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 规则 DSL 解析（工单 0451 BC1，semgrep 规则结构子集）。
 * 规则定义（id/语言/severity/pattern/exclude 排除模式列表）→规则对象；
 * 重复 id/空 pattern/非法 severity/未知 metavariable 类型拒绝并报告字段。纯函数。
 */
public class RuleParser {

    /** 严重级别 */
    public enum Severity {
        ERROR, WARN, INFO
    }

    /** 规则对象 */
    public record Rule(String id, String language, Severity severity, String pattern,
                       List<String> excludes, Set<String> metavariables) {
    }

    /** 解析异常（带字段报告） */
    public static class RuleParseException extends IllegalArgumentException {
        public RuleParseException(String message) {
            super(message);
        }
    }

    /** 解析规则清单：重复 id 拒绝 */
    public List<Rule> parse(List<RuleDefinition> definitions) {
        Set<String> seenIds = new LinkedHashSet<>();
        List<Rule> rules = new ArrayList<>();
        for (RuleDefinition definition : definitions) {
            if (!seenIds.add(definition.id())) {
                throw new RuleParseException("重复规则 id: " + definition.id());
            }
            rules.add(parseOne(definition));
        }
        return rules;
    }

    private Rule parseOne(RuleDefinition definition) {
        if (definition.id() == null || definition.id().isBlank()) {
            throw new RuleParseException("规则 id 为空");
        }
        if (definition.pattern() == null || definition.pattern().isBlank()) {
            throw new RuleParseException("规则 " + definition.id() + " pattern 为空");
        }
        Severity severity;
        try {
            severity = Severity.valueOf(definition.severity());
        } catch (Exception e) {
            throw new RuleParseException("规则 " + definition.id() + " 非法 severity: " + definition.severity());
        }
        Set<String> metavariables = new LinkedHashSet<>();
        for (String token : PatternMatcher.splitTokens(definition.pattern())) {
            if (token.startsWith("$") && token.length() > 1 && !token.equals("...")) {
                String name = token.substring(1);
                if (!name.matches("[A-Z][A-Z0-9_]*")) {
                    throw new RuleParseException("规则 " + definition.id() + " 非法 metavariable: " + token);
                }
                metavariables.add(name);
            }
        }
        return new Rule(definition.id(), definition.language(), severity, definition.pattern(),
                List.copyOf(definition.excludes()), metavariables);
    }

    /** 规则定义入参 */
    public record RuleDefinition(String id, String language, String severity, String pattern,
                                 List<String> excludes) {
    }
}
