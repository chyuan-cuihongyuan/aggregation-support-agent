package cn.chyuan.ai.domain.grammarkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文法规则表（工单 1220 FH1 / 1221 FH2，antlr 思想）。
 * 规则声明有序；重复名拒绝；引用未定义规则在 validate 拒绝；
 * 全大写名为词法 TOKEN 规则（可挂多字面量），小写为解析规则；
 * 解析规则体内字面量按首现序自动登记为隐式词法符号。
 */
public final class Grammar {

    private final Map<String, Pattern> rules = new LinkedHashMap<>();
    private final Map<String, List<String>> tokenLiterals = new LinkedHashMap<>();
    private final List<String> implicitLiterals = new ArrayList<>();

    /** 声明解析规则（小写）；全大写词法 TOKEN 须经 token() 声明；重复名拒绝 */
    public void rule(String name, Pattern pattern) {
        requireName(name);
        if (isLexerRule(name)) {
            throw new IllegalArgumentException("词法 TOKEN 规则请用 token() 声明: " + name);
        }
        if (rules.containsKey(name)) {
            throw new IllegalArgumentException("重复规则: " + name);
        }
        rules.put(name, pattern);
        collectImplicit(pattern);
    }

    /** 词法 TOKEN 挂字面量（多字面量任一命中即该 TOKEN） */
    public void token(String name, String... literals) {
        requireName(name);
        if (!isLexerRule(name)) {
            throw new IllegalArgumentException("词法规则须全大写: " + name);
        }
        if (rules.containsKey(name)) {
            throw new IllegalArgumentException("重复规则: " + name);
        }
        rules.put(name, Pattern.empty());
        for (String literal : literals) {
            if (literal == null || literal.isEmpty()) {
                throw new IllegalArgumentException("字面量不得为空");
            }
            tokenLiterals.computeIfAbsent(name, k -> new ArrayList<>()).add(literal);
        }
    }

    /** 校验：引用未定义规则拒绝（含左递归初检由 ParserEngine 预测期判定） */
    public void validate() {
        for (Map.Entry<String, Pattern> entry : rules.entrySet()) {
            checkRefs(entry.getValue(), entry.getKey());
        }
    }

    private void checkRefs(Pattern pattern, String owner) {
        switch (pattern) {
            case Pattern.Ref ref -> {
                if (!rules.containsKey(ref.name())) {
                    throw new IllegalArgumentException("引用未定义规则: " + ref.name() + " (in " + owner + ")");
                }
            }
            case Pattern.Seq seq -> seq.parts().forEach(p -> checkRefs(p, owner));
            case Pattern.Alt alt -> alt.options().forEach(p -> checkRefs(p, owner));
            case Pattern.Many many -> checkRefs(many.body(), owner);
            case Pattern.Token ignored -> {
            }
            case Pattern.Empty ignored -> {
            }
        }
    }

    private void collectImplicit(Pattern pattern) {
        switch (pattern) {
            case Pattern.Token token -> {
                if (!implicitLiterals.contains(token.literal()) && !allTokenLiterals().contains(token.literal())) {
                    implicitLiterals.add(token.literal());
                }
            }
            case Pattern.Seq seq -> seq.parts().forEach(this::collectImplicit);
            case Pattern.Alt alt -> alt.options().forEach(this::collectImplicit);
            case Pattern.Many many -> collectImplicit(many.body());
            case Pattern.Ref ignored -> {
            }
            case Pattern.Empty ignored -> {
            }
        }
    }

    /** 全大写即词法规则 */
    public static boolean isLexerRule(String name) {
        return !name.isEmpty() && name.chars().allMatch(Character::isUpperCase);
    }

    /** 词法候选：显式 TOKEN（声明序优先）+ 隐式字面量（首现序） */
    public List<String> explicitTokenLiterals(String tokenName) {
        return tokenLiterals.getOrDefault(tokenName, List.of());
    }

    public List<String> tokenNames() {
        return List.copyOf(tokenLiterals.keySet());
    }

    public List<String> implicits() {
        return List.copyOf(implicitLiterals);
    }

    public Pattern body(String name) {
        Pattern pattern = rules.get(name);
        if (pattern == null) {
            throw new IllegalArgumentException("未定义规则: " + name);
        }
        return pattern;
    }

    public boolean defined(String name) {
        return rules.containsKey(name);
    }

    public List<String> ruleNames() {
        return List.copyOf(rules.keySet());
    }

    private java.util.Set<String> allTokenLiterals() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        tokenLiterals.values().forEach(out::addAll);
        return out;
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("规则名不得为空");
        }
    }
}
