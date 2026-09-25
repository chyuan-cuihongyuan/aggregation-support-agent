package cn.chyuan.ai.domain.vmkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 词法（工单 0801 CQ1，cpython 编译器思想）。
 * 数字/字符串/标识符/运算符/关键字记号与行列定位/非法字符报错定位。
 */
public final class Lexer {

    public enum Kind { INT, STRING, IDENT, KEYWORD, OP, EOF }

    public record Token(Kind kind, String text, long num, int line, int col) {
        @Override
        public String toString() {
            return kind + "(" + text + ")@" + line + ":" + col;
        }
    }

    private static final List<String> KEYWORDS = List.of("if", "else", "while", "break", "continue",
            "def", "return", "true", "false", "try", "catch", "finally", "throw");
    private static final String[] OPS = {"==", "!=", "<=", ">=", "&&", "||",
            "+", "-", "*", "/", "%", "<", ">", "=", "!", "(", ")", "{", "}", ",", ";"};

    private final String src;
    private int at = 0;
    private int line = 1;
    private int col = 1;

    public Lexer(String src) {
        this.src = src;
    }

    public List<Token> lex() {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipSpace();
            if (at >= src.length()) {
                out.add(new Token(Kind.EOF, "", 0, line, col));
                return out;
            }
            int tl = line;
            int tc = col;
            char c = src.charAt(at);
            if (Character.isDigit(c)) {
                long v = 0;
                while (at < src.length() && Character.isDigit(src.charAt(at))) {
                    v = v * 10 + (src.charAt(at) - '0');
                    advance();
                }
                out.add(new Token(Kind.INT, String.valueOf(v), v, tl, tc));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                StringBuilder sb = new StringBuilder();
                while (at < src.length() && (Character.isLetterOrDigit(src.charAt(at)) || src.charAt(at) == '_')) {
                    sb.append(src.charAt(at));
                    advance();
                }
                String word = sb.toString();
                out.add(new Token(KEYWORDS.contains(word) ? Kind.KEYWORD : Kind.IDENT, word, 0, tl, tc));
                continue;
            }
            if (c == '"') {
                advance();
                StringBuilder sb = new StringBuilder();
                while (at < src.length() && src.charAt(at) != '"') {
                    if (src.charAt(at) == '\\' && at + 1 < src.length()) {
                        advance();
                        sb.append(switch (src.charAt(at)) {
                            case 'n' -> {
                                advance();
                                yield "\n";
                            }
                            case 't' -> {
                                advance();
                                yield "\t";
                            }
                            default -> {
                                yield String.valueOf(src.charAt(at++));
                            }
                        });
                        continue;
                    }
                    sb.append(src.charAt(at));
                    advance();
                }
                if (at >= src.length()) {
                    throw new IllegalArgumentException("字符串未闭合 @" + tl + ":" + tc);
                }
                advance();
                out.add(new Token(Kind.STRING, sb.toString(), 0, tl, tc));
                continue;
            }
            boolean matched = false;
            for (String op : OPS) {
                if (src.startsWith(op, at)) {
                    for (int i = 0; i < op.length(); i++) {
                        advance();
                    }
                    out.add(new Token(Kind.OP, op, 0, tl, tc));
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                throw new IllegalArgumentException("非法字符 '" + c + "' @" + tl + ":" + tc);
            }
        }
    }

    private void skipSpace() {
        while (at < src.length()) {
            char c = src.charAt(at);
            if (c == '#') {
                while (at < src.length() && src.charAt(at) != '\n') {
                    advance();
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                advance();
                continue;
            }
            return;
        }
    }

    private void advance() {
        if (src.charAt(at) == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        at++;
    }
}
