package cn.chyuan.ai.domain.desiredkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.chyuan.ai.domain.desiredkernel.service.ConfigModel.*;

/**
 * HCL 子集配置解析器（工单 0671 CB1，terraform 思想）。
 * 块/标签/属性与字符串-数字-布尔-列表-引用值/换行分隔/
 * 非法字符与括号缺失错误定位（行号）/重复资源标识拒绝。
 */
public final class ConfigParser {

    public record ParseError(String message, int line) {
    }

    public static Config parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("配置文本不得为空");
        }
        Lexer lexer = new Lexer(text);
        List<Token> tokens = lexer.tokenize();
        Parser parser = new Parser(tokens);
        return parser.parseConfig();
    }

    private record Token(String kind, String text, int line) {
    }

    private static final class Lexer {
        private final String src;
        private int pos;
        private int line = 1;

        Lexer(String src) {
            this.src = src;
        }

        List<Token> tokenize() {
            List<Token> out = new ArrayList<>();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '\n') {
                    out.add(new Token("NL", "\n", line++));
                    pos++;
                } else if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == '#') {
                    while (pos < src.length() && src.charAt(pos) != '\n') {
                        pos++;
                    }
                } else if (c == '"') {
                    out.add(string());
                } else if (Character.isDigit(c) || (c == '-' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1)))) {
                    out.add(number());
                } else if (Character.isJavaIdentifierStart(c)) {
                    out.add(ident());
                } else if ("{}[],=.".indexOf(c) >= 0) {
                    out.add(new Token(String.valueOf(c), String.valueOf(c), line));
                    pos++;
                } else {
                    throw new IllegalArgumentException("第 " + line + " 行非法字符 '" + c + "'");
                }
            }
            out.add(new Token("EOF", "", line));
            return out;
        }

        private Token string() {
            int startLine = line;
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length() && src.charAt(pos) != '"') {
                if (src.charAt(pos) == '\n') {
                    throw new IllegalArgumentException("第 " + startLine + " 行字符串未闭合");
                }
                sb.append(src.charAt(pos));
                pos++;
            }
            if (pos >= src.length()) {
                throw new IllegalArgumentException("第 " + startLine + " 行字符串未闭合");
            }
            pos++;
            return new Token("STR", sb.toString(), startLine);
        }

        private Token number() {
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.' || src.charAt(pos) == '-')) {
                pos++;
            }
            return new Token("NUM", src.substring(start, pos), line);
        }

        private Token ident() {
            int start = pos;
            while (pos < src.length() && Character.isJavaIdentifierPart(src.charAt(pos))) {
                pos++;
            }
            return new Token("IDENT", src.substring(start, pos), line);
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Config parseConfig() {
            List<Block> blocks = new ArrayList<>();
            skipNl();
            while (!peek("EOF")) {
                blocks.add(parseBlock());
                skipNl();
            }
            return new Config(List.copyOf(blocks));
        }

        private Block parseBlock() {
            Token type = expect("IDENT");
            List<String> labels = new ArrayList<>();
            while (peek("STR") || peek("IDENT")) {
                labels.add(next().text());
            }
            expect("{");
            Map<String, Value> attrs = new LinkedHashMap<>();
            skipNl();
            while (!peek("}") && !peek("EOF")) {
                Token key = expect("IDENT");
                expect("=");
                attrs.put(key.text(), parseValue());
                skipNl();
            }
            expect("}");
            return new Block(type.text(), List.copyOf(labels), attrs);
        }

        private Value parseValue() {
            Token t = next();
            switch (t.kind()) {
                case "STR" -> {
                    return new Str(t.text());
                }
                case "NUM" -> {
                    return new Num(Double.parseDouble(t.text()));
                }
                case "IDENT" -> {
                    return switch (t.text()) {
                        case "true" -> new Bool(true);
                        case "false" -> new Bool(false);
                        default -> ref(t);
                    };
                }
                case "[" -> {
                    List<Value> items = new ArrayList<>();
                    skipNl();
                    while (!peek("]")) {
                        items.add(parseValue());
                        skipNl();
                        if (peek(",")) {
                            next();
                            skipNl();
                        }
                    }
                    expect("]");
                    return new ListV(List.copyOf(items));
                }
                default -> throw new IllegalArgumentException("第 " + t.line() + " 行意外的值记号 " + t.kind());
            }
        }

        /** 引用路径：server.db.host 或 resource.web 简写段 */
        private Value ref(Token first) {
            List<String> path = new ArrayList<>();
            path.add(first.text());
            while (peek(".")) {
                next();
                Token seg = expect("IDENT");
                path.add(seg.text());
            }
            return new Ref(List.copyOf(path));
        }

        private boolean peek(String kind) {
            return tokens.get(pos).kind().equals(kind);
        }

        private Token next() {
            return tokens.get(pos++);
        }

        private Token expect(String kind) {
            Token t = tokens.get(pos);
            if (!t.kind().equals(kind)) {
                throw new IllegalArgumentException("第 " + t.line() + " 行期望 " + kind + " 实为 " + t.kind()
                        + (kind.equals("}") ? "（块括号缺失？）" : ""));
            }
            pos++;
            return t;
        }

        private void skipNl() {
            while (peek("NL") || peek(",")) {
                pos++;
            }
        }
    }

    /** 重复资源标识检查（type.name 唯一） */
    public static void requireUniqueResources(Config config) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Block block : config.byType("resource")) {
            String id = block.identity();
            if (seen.put(id, 1) != null) {
                throw new IllegalArgumentException("重复资源标识：" + id);
            }
        }
    }
}
