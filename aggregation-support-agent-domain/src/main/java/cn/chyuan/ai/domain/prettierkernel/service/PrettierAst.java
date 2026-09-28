package cn.chyuan.ai.domain.prettierkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * AST-lite 表达式树（工单 0985 EG2，prettier 思想）。
 * 标识符/数字/字符串字面量与调用表达式构建/括号嵌套/语法错误拒绝。
 */
public final class PrettierAst {

    /** 节点：literal 或 call */
    public static final class Node {
        public final String kind;
        public final String value;
        public final List<Node> children;

        private Node(String kind, String value, List<Node> children) {
            this.kind = kind;
            this.value = value;
            this.children = children;
        }

        static Node literal(String kind, String value) {
            return new Node(kind, value, List.of());
        }

        static Node call(String name, List<Node> args) {
            return new Node("call", name, List.copyOf(args));
        }
    }

    private PrettierAst() {
    }

    /** 解析程序：表达式以 ; 分隔，允许可选尾分号 */
    public static List<Node> parseProgram(String source) {
        List<PrettierLexer.Token> tokens = PrettierLexer.tokenize(source);
        Parser parser = new Parser(tokens);
        List<Node> program = new ArrayList<>();
        while (!parser.atEnd()) {
            program.add(parser.parseExpr());
            if (parser.match(";")) {
                continue;
            }
            if (!parser.atEnd()) {
                throw new IllegalArgumentException("多余尾随记号: " + parser.peek().text());
            }
        }
        return program;
    }

    /** 解析单个表达式 */
    public static Node parse(String source) {
        List<Node> program = parseProgram(source);
        if (program.size() != 1) {
            throw new IllegalArgumentException("应为单表达式: " + source);
        }
        return program.get(0);
    }

    private static final class Parser {
        private final List<PrettierLexer.Token> tokens;
        private int pos = 0;

        Parser(List<PrettierLexer.Token> tokens) {
            this.tokens = tokens;
        }

        PrettierLexer.Token peek() {
            if (atEnd()) {
                throw new IllegalArgumentException("表达式意外结束");
            }
            return tokens.get(pos);
        }

        boolean atEnd() {
            return pos >= tokens.size();
        }

        boolean match(String text) {
            if (!atEnd() && tokens.get(pos).text().equals(text)) {
                pos++;
                return true;
            }
            return false;
        }

        void expect(String text) {
            if (!match(text)) {
                throw new IllegalArgumentException("期望 '" + text + "' 实得 '" + (atEnd() ? "EOF" : peek().text()) + "'");
            }
        }

        Node parseExpr() {
            PrettierLexer.Token token = peek();
            switch (token.type()) {
                case PrettierLexer.Token.NUMBER -> {
                    pos++;
                    return Node.literal("number", token.text());
                }
                case PrettierLexer.Token.STRING -> {
                    pos++;
                    return Node.literal("string", token.text());
                }
                case PrettierLexer.Token.IDENT -> {
                    pos++;
                    if (!atEnd() && tokens.get(pos).text().equals("(")) {
                        expect("(");
                        List<Node> args = new ArrayList<>();
                        if (!match(")")) {
                            args.add(parseExpr());
                            while (match(",")) {
                                args.add(parseExpr());
                            }
                            expect(")");
                        }
                        return Node.call(token.text(), args);
                    }
                    return Node.literal("ident", token.text());
                }
                default -> throw new IllegalArgumentException("表达式语法错误: " + token.text());
            }
        }
    }
}
