package cn.chyuan.ai.domain.grammarkernel.service;

import java.util.List;

/**
 * 文法编译端口（工单 1227 FH8，antlr 思想）。
 * compile·lex·parse 入口统一编排：规则声明·词法切分·LL 预测·解析树·错误恢复组合管线/
 * protokernel 消息字段树形状只读联动（FieldSpec: name/number/type/label/packed 字段名对齐，
 * 不 import protokernel）/grammar-kernel.enabled 默认关（开启才改变行为）。
 */
public interface GrammarPort {

    /** 声明解析规则（FH1） */
    void rule(String name, Pattern pattern);

    /** 声明词法 TOKEN 及字面量（FH2） */
    void token(String name, String... literals);

    /** 切词（FH2） */
    Lexer.LexResult lex(String input);

    /** 解析：产出解析树与错误列表（FH5/FH6/FH7） */
    ParserEngine.ParseResult parse(String startRule, String input);

    /** 规则清单（FH1） */
    List<String> rules();

    /** 校验文法：引用未定义规则拒绝（FH1） */
    void validate();

    /** protokernel 消息字段树形状只读联动（FieldSpec: name/number/type/label/packed） */
    List<String> fieldTreeShape();

    static GrammarPort inMemory() {
        return new GrammarServer();
    }
}
