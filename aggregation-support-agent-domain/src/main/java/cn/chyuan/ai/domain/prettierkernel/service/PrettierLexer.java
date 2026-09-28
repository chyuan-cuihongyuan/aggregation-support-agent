package cn.chyuan.ai.domain.prettierkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 词法（工单 0984 EG1，prettier 思想）。
 * 记号切分：标识符/整数/字符串字面量/标点；未闭合字符串与非法字符拒绝。
 */
public final class PrettierLexer {

    /** 记号：类型 + 文本 */
    public record Token(String type, String text) {

        public static final String IDENT = "ident";
        public static final String NUMBER = "number";
        public static final String STRING = "string";
        public static final String PUNCT = "punct";
    }

    private PrettierLexer() {
    }

    /** 切分：空白分隔，字符串字面量整段保留 */
    public static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Token.IDENT, source.substring(start, i)));
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i;
                while (i < source.length() && Character.isDigit(source.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Token.NUMBER, source.substring(start, i)));
                continue;
            }
            if (c == '"') {
                int end = source.indexOf('"', i + 1);
                if (end == -1) {
                    throw new IllegalArgumentException("字符串未闭合: " + source.substring(i));
                }
                tokens.add(new Token(Token.STRING, source.substring(i + 1, end)));
                i = end + 1;
                continue;
            }
            if ("(),;".indexOf(c) >= 0) {
                tokens.add(new Token(Token.PUNCT, String.valueOf(c)));
                i++;
                continue;
            }
            throw new IllegalArgumentException("非法字符 '" + c + "'");
        }
        return tokens;
    }
}
