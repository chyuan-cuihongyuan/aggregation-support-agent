package cn.chyuan.ai.domain.scankernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模式匹配引擎（工单 0452 BC2 + 0453 BC3，semgrep 模式思想）。
 * token 序列匹配子集：字面 token 一一对应 / {@code $X} metavariable 匹配任意单 token
 * / {@code ...} 匹配任意 token 序列（含空）/ 匹配结果行列定位；
 * 同名 metavariable 绑定一致性（不一致匹配失败）；绑定环境输出。
 */
public class PatternMatcher {

    /** 匹配命中 */
    public record Match(int line, int column, Map<String, String> bindings, String matchedText) {
    }

    /** 源码 token */
    public record Token(String text, int line, int column) {
    }

    /** 词法切分：保留操作符单字符为独立 token，行号列号随行 */
    public static List<Token> tokenize(String content) {
        List<Token> tokens = new ArrayList<>();
        if (content == null) {
            return tokens;
        }
        int line = 1;
        int column = 1;
        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '\n') {
                line++;
                column = 1;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                column++;
                i++;
                continue;
            }
            int start = i;
            while (i < content.length() && !Character.isWhitespace(content.charAt(i))
                    && !isOperator(content.charAt(i))) {
                i++;
            }
            if (i == start) {
                i++;
            }
            tokens.add(new Token(content.substring(start, i), line, column));
            column += i - start;
        }
        return tokens;
    }

    private static boolean isOperator(char c) {
        return c == '=' || c == '(' || c == ')' || c == '{' || c == '}' || c == ';' || c == ',' || c == '<' || c == '>';
    }

    /** 模式切分：与源码词法同口径（操作符独立成 token），供 findAll 与规则解析共用 */
    public static String[] splitTokens(String pattern) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        String stripped = pattern.strip();
        while (i < stripped.length()) {
            char c = stripped.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            while (i < stripped.length() && !Character.isWhitespace(stripped.charAt(i))
                    && !isOperator(stripped.charAt(i))) {
                i++;
            }
            if (i == start) {
                i++;
            }
            tokens.add(stripped.substring(start, i));
        }
        return tokens.toArray(new String[0]);
    }

    /** 全量匹配：返回模式在 token 流中的全部命中（按出现序） */
    public List<Match> findAll(List<Token> tokens, String pattern) {
        String[] patternTokens = splitTokens(pattern);
        List<Match> matches = new ArrayList<>();
        for (int start = 0; start < tokens.size(); start++) {
            Map<String, String> bindings = new HashMap<>();
            int end = matchHere(tokens, start, patternTokens, 0, bindings);
            if (end >= 0) {
                StringBuilder text = new StringBuilder();
                for (int i = start; i < end; i++) {
                    text.append(tokens.get(i).text()).append(' ');
                }
                matches.add(new Match(tokens.get(start).line(), tokens.get(start).column(),
                        Map.copyOf(bindings), text.toString().strip()));
            }
        }
        return matches;
    }

    /** 递归匹配：返回匹配结束位置（不含），失败 -1 */
    private int matchHere(List<Token> tokens, int position, String[] pattern, int patternIndex,
                          Map<String, String> bindings) {
        if (patternIndex >= pattern.length) {
            return position;
        }
        String current = pattern[patternIndex];
        if (current.equals("...")) {
            // 非贪婪：优先少匹配（尝试后续模式立即继续）
            for (int skip = 0; position + skip <= tokens.size(); skip++) {
                Map<String, String> trial = new HashMap<>(bindings);
                int end = matchHere(tokens, position + skip, pattern, patternIndex + 1, trial);
                if (end >= 0) {
                    bindings.putAll(trial);
                    return end;
                }
            }
            return -1;
        }
        if (position >= tokens.size()) {
            return -1;
        }
        Token token = tokens.get(position);
        if (current.startsWith("$")) {
            String name = current.substring(1);
            String previous = bindings.get(name);
            if (previous != null && !previous.equals(token.text())) {
                return -1;
            }
            bindings.put(name, token.text());
            int end = matchHere(tokens, position + 1, pattern, patternIndex + 1, bindings);
            if (end < 0) {
                bindings.remove(name);
            }
            return end;
        }
        if (!token.text().equals(current)) {
            return -1;
        }
        return matchHere(tokens, position + 1, pattern, patternIndex + 1, bindings);
    }
}
