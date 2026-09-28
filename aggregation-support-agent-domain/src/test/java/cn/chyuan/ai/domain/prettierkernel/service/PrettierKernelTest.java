package cn.chyuan.ai.domain.prettierkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 排版打印内核测试（工单 0984-0991 EG1-EG8，prettier 思想）。
 * 词法/AST-lite/doc IR/fits 判定/宽度适配打印/缩进换行/fill 填充/端口幂等打印。
 */
class PrettierKernelTest {

    @Test
    void lexerTokensAndReject() {
        List<PrettierLexer.Token> tokens = PrettierLexer.tokenize("f(a, \"s\", 42)");
        assertEquals(8, tokens.size());
        assertEquals("ident", tokens.get(0).type());
        assertEquals("f", tokens.get(0).text());
        assertEquals("ident", tokens.get(2).type());
        assertEquals("a", tokens.get(2).text());
        assertEquals("string", tokens.get(4).type());
        assertEquals("s", tokens.get(4).text());
        assertEquals("number", tokens.get(6).type());
        assertEquals("42", tokens.get(6).text());
        assertEquals("punct", tokens.get(7).type());
        assertThrows(IllegalArgumentException.class, () -> PrettierLexer.tokenize("\"unclosed"), "未闭合字符串拒绝");
        assertThrows(IllegalArgumentException.class, () -> PrettierLexer.tokenize("a @ b"), "非法字符拒绝");
    }

    @Test
    void astBuildAndReject() {
        PrettierAst.Node call = PrettierAst.parse("f(a, 1, g(b))");
        assertEquals("call", call.kind);
        assertEquals("f", call.value);
        assertEquals(3, call.children.size(), "三参数");
        assertEquals("call", call.children.get(2).kind, "嵌套调用");
        assertEquals("b", call.children.get(2).children.get(0).value);

        PrettierAst.Node literal = PrettierAst.parse("42");
        assertEquals("number", literal.kind);
        assertThrows(IllegalArgumentException.class, () -> PrettierAst.parse("f("), "括号未闭拒绝");
        assertThrows(IllegalArgumentException.class, () -> PrettierAst.parse("f(a,,b)"), "逗号后缺参数拒绝");
        assertThrows(IllegalArgumentException.class, () -> PrettierAst.parse("f(a) x"), "尾随记号拒绝");
        assertThrows(IllegalArgumentException.class, () -> PrettierAst.parse("(a)"), "括号表达式拒绝");
        List<PrettierAst.Node> program = PrettierAst.parseProgram("a; b; c;");
        assertEquals(3, program.size(), "分号程序");
    }

    @Test
    void docIrPrimitivesAndConcat() {
        Doc doc = Doc.concat(List.of(
                Doc.text("f("),
                Doc.indent(Doc.concat(Doc.text("a"), Doc.concat(Doc.text(","), Doc.line()), Doc.text("b"))),
                Doc.text(")")));
        assertTrue(doc instanceof Concat);
        Printer printer = new Printer(80, 2);
        assertEquals("f(a, b)", printer.print(doc), "展平态拼接");
        Docs.fits(doc, 80);
    }

    @Test
    void fitsDecision() {
        assertTrue(Docs.fits(Doc.text("abc"), 3), "恰好放下");
        assertFalse(Docs.fits(Doc.text("abcd"), 3), "超宽必断");
        assertTrue(Docs.fits(Doc.softline(), 0), "软换行零宽展平");
        assertFalse(Docs.fits(Doc.line(), 10), "普通换行不展平");
        assertFalse(Docs.fits(Doc.hardline(), 10), "硬换行必断");
        Doc group = Doc.group(Doc.concat(Doc.text("ab"), Doc.softline(), Doc.text("cd")));
        assertTrue(Docs.fits(group, 4), "组展平后计入");
        assertFalse(Docs.fits(group, 3), "组展平超宽");
        Doc nested = Doc.group(Doc.indent(Doc.text("abcdef")));
        assertFalse(Docs.fits(nested, 5), "缩进内容计入宽度");
    }

    @Test
    void widthAdaptivePrint() {
        Printer printer = new Printer(40, 2);
        assertEquals("f(a, b, c)", printer.print(InMemoryPrinter.layout(PrettierAst.parse("f(a,b,c)"))), "未超宽展平");
        Printer narrow = new Printer(6, 2);
        String broken = narrow.print(InMemoryPrinter.layout(PrettierAst.parse("f(a,b,c)")));
        assertEquals("f(\n  a,\n  b,\n  c)", broken, "超宽断组换行缩进");
        assertThrows(IllegalArgumentException.class, () -> new Printer(0, 2), "行宽非法拒绝");
    }

    @Test
    void indentLevelsAccumulate() {
        Printer narrow = new Printer(4, 2);
        String nested = narrow.print(InMemoryPrinter.layout(PrettierAst.parse("outer(inner(a,b),c)")));
        String[] lines = nested.split("\n");
        assertEquals("outer(", lines[0]);
        assertEquals("  inner(", lines[1], "一层缩进 2 空格");
        assertEquals("    a,", lines[2], "两层缩进 4 空格");
        assertEquals("    b),", lines[3], "闭括号与逗号贴随末参");
        assertEquals("  c)", lines[4], "回到一层缩进");
        assertEquals(5, lines.length);
    }

    @Test
    void fillPacksByWidth() {
        Printer printer = new Printer(8, 2);
        List<Doc> items = List.of(Doc.text("aaa"), Doc.text("bbb"), Doc.text("ccc"));
        assertEquals("aaa bbb\nccc", printer.fill(items), "按行贪心装填");
        assertEquals("aaa\nbbb\nccc", new Printer(3, 2).fill(items), "窄行逐个成行");
        List<Doc> wide = List.of(Doc.text("x"), Doc.text("toolongitem"));
        assertEquals("x\ntoolongitem", new Printer(8, 2).fill(wide), "单项超宽独立成行");
    }

    @Test
    void portIdempotentFormatAndDocLink() {
        PrinterPort port = PrinterPort.inMemory();
        String flat = port.format("f(a,b)", 40);
        assertEquals("f(a, b)", flat, "规范空格输出");
        assertEquals(flat, port.format(flat, 40), "幂等打印：再格式化不变");

        String broken = port.format("f(a,b,c)", 6);
        assertEquals(broken, port.format(broken, 6), "断行形态幂等");
        assertEquals("f(\n  a,\n  b,\n  c)", broken);

        String program = port.format("f(a,b); g(c); 42", 40);
        assertEquals("f(a, b);\ng(c);\n42", program, "多语句分号加硬换行分隔");
        assertEquals(program, port.format(program, 40), "程序级幂等");

        assertEquals("doc://editor/f(a, b)", PrinterPort.documentOf(flat), "editorkernel 文档形态只读联动");
        assertEquals("doc://editor/f(\\n  a)", PrinterPort.documentOf("f(\n  a)"), "换行转义形态");
        assertThrows(IllegalArgumentException.class, () -> port.format("f(,)", 40), "语法错误拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.format("", 40), "空程序拒绝");
    }
}
