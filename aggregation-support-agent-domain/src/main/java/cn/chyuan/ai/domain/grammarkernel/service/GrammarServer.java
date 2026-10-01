package cn.chyuan.ai.domain.grammarkernel.service;

import java.util.List;

/**
 * 文法编译组合实现（工单 1227 FH8，antlr 思想）。
 * 规则表 + 词法器 + 解析引擎一体编排：lex 走最长匹配，parse 走 LL 预测与错误恢复。
 */
public final class GrammarServer implements GrammarPort {

    private final Grammar grammar = new Grammar();

    @Override
    public void rule(String name, Pattern pattern) {
        grammar.rule(name, pattern);
    }

    @Override
    public void token(String name, String... literals) {
        grammar.token(name, literals);
    }

    @Override
    public Lexer.LexResult lex(String input) {
        grammar.validate();
        return new Lexer().tokenize(grammar, input);
    }

    @Override
    public ParserEngine.ParseResult parse(String startRule, String input) {
        return new ParserEngine(grammar).parse(startRule, input);
    }

    @Override
    public List<String> rules() {
        return grammar.ruleNames();
    }

    @Override
    public void validate() {
        grammar.validate();
    }

    @Override
    public List<String> fieldTreeShape() {
        return List.of("name", "number", "type", "label", "packed");
    }
}
