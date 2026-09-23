package cn.chyuan.ai.domain.templatekernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板词法（工单 0626 BW1，jinja2 词法思想）。
 * 文本/变量 {{ … }}/标签 {% … %} 三类记号切分/
 * 未闭合定界符报告位置拒绝/记号位置（行:列）携带。
 */
public final class TemplateLexer {

    /** 记号类型 */
    public enum Kind {
        TEXT, VAR, TAG
    }

    /** 记号 */
    public record Token(Kind kind, String source, int line, int col) {
    }

    private TemplateLexer() {
    }

    /** 切分：{{ expr }} 与 {% stmt %}，其余为文本 */
    public static List<Token> lex(String template) {
        if (template == null) {
            throw new IllegalArgumentException("模板不得为 null");
        }
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int line = 1;
        int col = 1;
        StringBuilder text = new StringBuilder();
        int textLine = 1;
        int textCol = 1;
        while (i < template.length()) {
            boolean var = template.startsWith("{{", i);
            boolean tag = !var && template.startsWith("{%", i);
            if (var || tag) {
                if (text.length() > 0) {
                    tokens.add(new Token(Kind.TEXT, text.toString(), textLine, textCol));
                    text.setLength(0);
                }
                String close = var ? "}}" : "%}";
                int end = template.indexOf(close, i + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("未闭合定界符 "
                            + (var ? "{{" : "{%") + " 于 " + line + ":" + col);
                }
                tokens.add(new Token(var ? Kind.VAR : Kind.TAG,
                        template.substring(i + 2, end).trim(), line, col));
                for (int k = i; k < end + close.length(); k++) {
                    if (template.charAt(k) == '\n') {
                        line++;
                        col = 1;
                    } else {
                        col++;
                    }
                }
                i = end + close.length();
                textLine = line;
                textCol = col;
                continue;
            }
            if (text.length() == 0) {
                textLine = line;
                textCol = col;
            }
            text.append(template.charAt(i));
            if (template.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
            i++;
        }
        if (text.length() > 0) {
            tokens.add(new Token(Kind.TEXT, text.toString(), textLine, textCol));
        }
        return tokens;
    }
}
