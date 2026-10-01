package cn.chyuan.ai.domain.grammarkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文法编译内核测试（工单 1220-1227 FH1-FH8，antlr 思想）。
 * 文法规则/词法规则/序列选择/重复可选/LL 预测/解析树/错误恢复/端口组合管线。
 */
class GrammarKernelTest {

    @Test
    void grammarRules() {
        GrammarPort port = GrammarPort.inMemory();
        port.rule("expr", Pattern.seq(Pattern.ref("num")));
        port.rule("num", Pattern.alt(Pattern.lit("1"), Pattern.lit("2")));
        assertEquals(List.of("expr", "num"), port.rules(), "声明序保留");
        assertThrows(IllegalArgumentException.class,
                () -> port.rule("expr", Pattern.lit("x")), "重复规则拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> port.rule("", Pattern.lit("x")), "空名拒绝");
        port.rule("bad", Pattern.ref("ghost"));
        assertThrows(IllegalArgumentException.class, port::validate, "引用未定义规则拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> port.rule("UPPER", Pattern.lit("x")), "全大写须走 token() 声明");
    }

    @Test
    void lexerRules() {
        GrammarPort port = GrammarPort.inMemory();
        port.token("LE", "<=");
        port.token("KW", "if", "else");
        port.rule("stmt", Pattern.seq(Pattern.ref("cond")));
        port.rule("cond", Pattern.alt(Pattern.lit("if"), Pattern.lit("<")));
        Lexer.LexResult result = port.lex("if<=");
        List<String> types = result.tokens().stream().map(Lexer.Token::type).toList();
        assertEquals(List.of("KW", "LE", "<EOF>"), types, "最长匹配优先且零错误");
        assertTrue(result.errors().isEmpty());
        GrammarPort tiePort = GrammarPort.inMemory();
        tiePort.token("A", "x");
        tiePort.token("B", "x");
        tiePort.rule("t", Pattern.lit("x"));
        assertEquals("A", tiePort.lex("x").tokens().get(0).type(), "同长先声明者胜");
        Lexer.LexResult errors = port.lex("if$");
        assertEquals(1, errors.errors().size(), "未匹配字符报词法错误");
        assertEquals(List.of("KW", "<EOF>"), errors.tokens().stream().map(Lexer.Token::type).toList(),
                "错误字符跳过恢复");
    }

    @Test
    void seqAndAlt() {
        GrammarPort port = GrammarPort.inMemory();
        port.rule("s", Pattern.seq(Pattern.lit("a"), Pattern.alt(Pattern.lit("b"), Pattern.lit("c"))));
        ParserEngine.ParseResult ok = port.parse("s", "ab");
        assertTrue(ok.errors().isEmpty());
        assertEquals("(s a b)", ok.tree().toStringTree());
        ParserEngine.ParseResult altB = port.parse("s", "ac");
        assertTrue(altB.errors().isEmpty(), "选择按首集命中第二支");
        assertEquals("(s a c)", altB.tree().toStringTree());
        ParserEngine.ParseResult extra = port.parse("s", "abb");
        assertEquals(1, extra.errors().size(), "多余输入报告");
        assertTrue(extra.errors().get(0).message().contains("多余输入"));

        GrammarPort eps = GrammarPort.inMemory();
        eps.rule("e", Pattern.seq(Pattern.lit("x"), Pattern.empty()));
        assertTrue(eps.parse("e", "x").errors().isEmpty(), "ε 空串可选");
    }

    @Test
    void cardinality() {
        GrammarPort port = GrammarPort.inMemory();
        port.rule("s", Pattern.seq(Pattern.lit("a"), Pattern.star(Pattern.lit("b")), Pattern.lit("c")));
        assertTrue(port.parse("s", "ac").errors().isEmpty(), "* 零次合法");
        assertTrue(port.parse("s", "abbbc").errors().isEmpty(), "* 贪婪多次");
        ParserEngine.ParseResult greedy = port.parse("s", "abbc");
        assertTrue(greedy.errors().isEmpty(), "贪婪遇非首集停止");

        GrammarPort plus = GrammarPort.inMemory();
        plus.rule("p", Pattern.seq(Pattern.lit("a"), Pattern.plus(Pattern.lit("b"))));
        assertFalse(plus.parse("p", "a").errors().isEmpty(), "+ 缺一报缺失");
        assertTrue(plus.parse("p", "abb").errors().isEmpty());

        GrammarPort opt = GrammarPort.inMemory();
        opt.rule("o", Pattern.seq(Pattern.lit("a"), Pattern.opt(Pattern.lit("b"))));
        assertTrue(opt.parse("o", "ab").errors().isEmpty(), "? 取一");
        assertTrue(opt.parse("o", "a").errors().isEmpty(), "? 取零");
    }

    @Test
    void llPrediction() {
        GrammarPort port = GrammarPort.inMemory();
        port.rule("d", Pattern.alt(
                Pattern.seq(Pattern.lit("x"), Pattern.lit("1")),
                Pattern.seq(Pattern.lit("x"), Pattern.lit("2"))));
        assertThrows(IllegalStateException.class, () -> port.parse("d", "x1"),
                "分支首集相交即 LL 冲突不回溯");

        GrammarPort distinct = GrammarPort.inMemory();
        distinct.rule("p", Pattern.alt(Pattern.lit("1"), Pattern.lit("2")));
        assertEquals("(p 2)", distinct.parse("p", "2").tree().toStringTree(), "首集分派单支");

        GrammarPort left = GrammarPort.inMemory();
        left.rule("r", Pattern.seq(Pattern.ref("r")));
        assertThrows(IllegalStateException.class, () -> left.parse("r", "a"), "左递归拒绝");
    }

    @Test
    void parseTree() {
        GrammarPort port = GrammarPort.inMemory();
        port.rule("expr", Pattern.seq(Pattern.ref("num"),
                Pattern.star(Pattern.seq(Pattern.lit("+"), Pattern.ref("num")))));
        port.rule("num", Pattern.alt(Pattern.lit("1"), Pattern.lit("2")));
        ParserEngine.ParseResult result = port.parse("expr", "1+2");
        assertTrue(result.errors().isEmpty());
        assertEquals("(expr (num 1) + (num 2))", result.tree().toStringTree(), "规则节点+词法叶子");
        assertEquals("expr", result.tree().kind());
        assertEquals("num", result.tree().children().get(0).kind());
        assertEquals("1", result.tree().children().get(0).children().get(0).text());
        assertEquals(List.of("num", "+", "num"),
                result.tree().children().stream().map(ParserEngine.Node::kind).toList());
    }

    @Test
    void errorRecovery() {
        GrammarPort port = GrammarPort.inMemory();
        port.rule("expr", Pattern.seq(Pattern.ref("num"),
                Pattern.star(Pattern.seq(Pattern.lit("+"), Pattern.ref("num")))));
        port.rule("num", Pattern.alt(Pattern.lit("1"), Pattern.lit("2")));

        ParserEngine.ParseResult missing = port.parse("expr", "12");
        assertEquals(2, missing.errors().size(), "单 token 缺失报位置+EOF 分支跳过");
        assertTrue(missing.errors().get(0).message().contains("期望 '+' 实得 '2'"));
        assertTrue(missing.tree().toStringTree().contains("(num 1)"), "错误恢复后树仍产出");

        ParserEngine.ParseResult trailing = port.parse("expr", "1+2+");
        assertEquals(1, trailing.errors().size(), "尾部缺失报错不挂死");
        assertTrue(trailing.tree().toStringTree().contains("(num 2)"), "恢复续走已解析部分");

        ParserEngine.ParseResult clean = port.parse("expr", "2+1");
        assertTrue(clean.errors().isEmpty());
    }

    @Test
    void grammarPipeline() {
        GrammarPort port = GrammarPort.inMemory();
        port.token("PLUS", "+");
        port.rule("expr", Pattern.seq(Pattern.ref("num"),
                Pattern.star(Pattern.seq(Pattern.lit("+"), Pattern.ref("num")))));
        port.rule("num", Pattern.alt(Pattern.lit("1"), Pattern.lit("2")));
        port.validate();
        Lexer.LexResult lexed = port.lex("1+2");
        assertEquals(List.of("1", "PLUS", "2", "<EOF>"),
                lexed.tokens().stream().map(Lexer.Token::type).toList(), "显式 TOKEN 优先类型命名");
        ParserEngine.ParseResult result = port.parse("expr", "1+2");
        assertTrue(result.errors().isEmpty());
        assertEquals("(expr (num 1) + (num 2))", result.tree().toStringTree());
        assertEquals(List.of("name", "number", "type", "label", "packed"),
                port.fieldTreeShape(), "protokernel 消息字段树形状只读联动");
    }
}
