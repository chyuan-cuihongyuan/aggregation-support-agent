package cn.chyuan.ai.domain.grammarkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析引擎（工单 1222 FH3 / 1223 FH4 / 1224 FH5 / 1225 FH6 / 1226 FH7，antlr 思想）。
 * 递归下降：Seq 按序/Alt 按分支 first 集预测（冲突报错不回溯）/Many 贪婪基数；
 * 解析树产出规则节点与词法叶子；错误恢复采用单 token 删除一次重试，失败则跳过元素续走；
 * 根规则完成后剩余非 EOF 报多余输入。
 */
public final class ParserEngine {

    /** 解析树节点：kind=规则名或词法类型；词法叶子 text 为原文 */
    public record Node(String kind, String text, List<Node> children) {

        public String toStringTree() {
            if (children.isEmpty()) {
                return text.isEmpty() ? kind : text;
            }
            StringBuilder out = new StringBuilder("(").append(kind);
            for (Node child : children) {
                out.append(' ').append(child.toStringTree());
            }
            return out.append(')').toString();
        }
    }

    public record ParseError(int index, String message) {
    }

    public record ParseResult(Node tree, List<ParseError> errors) {
    }

    private final Grammar grammar;
    private final Lexer lexer = new Lexer();
    private List<Lexer.Token> tokens;
    private int pos;
    private final List<ParseError> errors = new ArrayList<>();
    private final java.util.Map<String, Integer> enteredAt = new java.util.HashMap<>();

    public ParserEngine(Grammar grammar) {
        this.grammar = grammar;
    }

    /** 解析入口：先 validate 再切词再递归下降 */
    public ParseResult parse(String startRule, String input) {
        grammar.validate();
        if (!grammar.defined(startRule)) {
            throw new IllegalArgumentException("未定义起始规则: " + startRule);
        }
        Lexer.LexResult lexed = lexer.tokenize(grammar, input);
        tokens = lexed.tokens();
        pos = 0;
        errors.addAll(lexed.errors().stream()
                .map(e -> new ParseError(e.index(), e.message()))
                .toList());
        Node tree = rule(startRule);
        if (pos < tokens.size() - 1) {
            errors.add(new ParseError(pos, "多余输入 '" + tokens.get(pos).text() + "'"));
        }
        return new ParseResult(tree, List.copyOf(errors));
    }

    private Node rule(String name) {
        Integer prev = enteredAt.put(name, pos);
        if (prev != null && prev == pos) {
            enteredAt.remove(name);
            throw new IllegalStateException("左递归拒绝: " + name);
        }
        try {
            Node node = new Node(name, "", new ArrayList<>());
            pattern(grammar.body(name), node);
            return node;
        } finally {
            enteredAt.remove(name);
        }
    }

    private void pattern(Pattern pattern, Node parent) {
        switch (pattern) {
            case Pattern.Token token -> literal(token.literal(), parent);
            case Pattern.Ref ref -> parent.children().add(rule(ref.name()));
            case Pattern.Empty ignored -> {
            }
            case Pattern.Seq seq -> seq.parts().forEach(part -> pattern(part, parent));
            case Pattern.Alt alt -> alternative(alt, parent);
            case Pattern.Many many -> repeated(many, parent);
        }
    }

    /** LL 预测：按分支 first 集选支；冲突不可恢复报错（不回溯） */
    private void alternative(Pattern.Alt alt, Node parent) {
        String look = tokens.get(pos).type();
        String lookText = tokens.get(pos).text();
        int hit = -1;
        for (int i = 0; i < alt.options().size(); i++) {
            Set<String> first = firstOf(alt.options().get(i), new LinkedHashSet<>());
            if (first.contains(look) || first.contains(lookText)) {
                if (hit >= 0) {
                    throw new IllegalStateException("LL 预测冲突: 分支 " + (hit + 1) + " 与 " + (i + 1)
                            + " 首集相交于 '" + lookText + "'");
                }
                hit = i;
            }
        }
        if (hit < 0) {
            errors.add(new ParseError(pos, "无匹配分支 '" + lookText + "'，按空分支跳过"));
            return;
        }
        pattern(alt.options().get(hit), parent);
    }

    /** 贪婪基数：while 首集命中且未到上限；不足下限报缺失 */
    private void repeated(Pattern.Many many, Node parent) {
        Set<String> first = firstOf(many.body(), new LinkedHashSet<>());
        int count = 0;
        while (count < many.max()) {
            String look = tokens.get(pos).type();
            String lookText = tokens.get(pos).text();
            boolean matches = pos < tokens.size() - 1
                    && (first.contains(look) || first.contains(lookText));
            if (!matches) {
                break;
            }
            int before = pos;
            pattern(many.body(), parent);
            if (pos == before) {
                break;
            }
            count++;
        }
        if (count < many.min()) {
            errors.add(new ParseError(pos, "缺少必需元素（" + count + "/" + many.min() + "）"));
        }
    }

    /** 字面量匹配：单 token 删除恢复——删一枚重试一次，再败则跳过元素 */
    private void literal(String expected, Node parent) {
        Lexer.Token current = tokens.get(pos);
        if (matches(current, expected)) {
            parent.children().add(new Node(current.type(), current.text(), List.of()));
            pos++;
            return;
        }
        if (current != Lexer.Token.EOF) {
            errors.add(new ParseError(pos, "期望 '" + expected + "' 实得 '" + current.text() + "'"));
            pos++;
            Lexer.Token retried = tokens.get(pos);
            if (matches(retried, expected)) {
                parent.children().add(new Node(retried.type(), retried.text(), List.of()));
                pos++;
            }
            return;
        }
        errors.add(new ParseError(pos, "期望 '" + expected + "' 实得输入结束"));
    }

    private boolean matches(Lexer.Token token, String expected) {
        return token.type().equals(expected) || token.text().equals(expected);
    }

    /** 上下文 first 集：Ref 递归展开（左递归拒绝），Seq/Alt 并集近似 */
    private Set<String> firstOf(Pattern pattern, Set<String> visiting) {
        Set<String> out = new LinkedHashSet<>();
        collect(pattern, visiting, out);
        return out;
    }

    private void collect(Pattern pattern, Set<String> visiting, Set<String> out) {
        switch (pattern) {
            case Pattern.Token token -> out.add(token.literal());
            case Pattern.Ref ref -> {
                if (ref.name().equals("<EOF>") || !visiting.add(ref.name())) {
                    return;
                }
                try {
                    collect(grammar.body(ref.name()), visiting, out);
                } finally {
                    visiting.remove(ref.name());
                }
            }
            case Pattern.Seq seq -> seq.parts().forEach(part -> collect(part, visiting, out));
            case Pattern.Alt alt -> alt.options().forEach(part -> collect(part, visiting, out));
            case Pattern.Many many -> collect(many.body(), visiting, out);
            case Pattern.Empty ignored -> {
            }
        }
    }
}
