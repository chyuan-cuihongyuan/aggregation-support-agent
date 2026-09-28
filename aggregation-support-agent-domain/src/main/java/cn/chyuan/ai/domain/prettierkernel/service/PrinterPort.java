package cn.chyuan.ai.domain.prettierkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 排版打印端口（工单 0991 EG8，prettier 思想）。
 * format 入口统一编排（词法→AST→doc IR→打印）/与 editorkernel 文档作字符串形态只读联动（泛型文本不 import）/
 * printer-kernel.enabled 默认关（开启才改变行为）。
 */
public interface PrinterPort {

    /** 格式化：表达式程序按行宽打印，幂等 */
    String format(String source, int lineWidth);

    /** editorkernel 文档形态只读联动：打印结果 → 编辑器文档串（形状数据不 import editorkernel） */
    static String documentOf(String printed) {
        return "doc://editor/" + printed.replace("\n", "\\n");
    }

    static PrinterPort inMemory() {
        return new InMemoryPrinter();
    }
}

final class InMemoryPrinter implements PrinterPort {

    @Override
    public String format(String source, int lineWidth) {
        List<PrettierAst.Node> program = PrettierAst.parseProgram(source);
        if (program.isEmpty()) {
            throw new IllegalArgumentException("空程序");
        }
        List<Doc> statements = new ArrayList<>();
        for (int i = 0; i < program.size(); i++) {
            if (i > 0) {
                statements.add(Doc.concat(Doc.text(";"), Doc.hardline()));
            }
            statements.add(layout(program.get(i)));
        }
        return new Printer(lineWidth, 2).print(Doc.concat(statements));
    }

    /** AST → doc IR：字面量为 text，调用为 group(indent(软行 + 逗号加 line)) 组合 */
    static Doc layout(PrettierAst.Node node) {
        if (!node.kind.equals("call")) {
            return Doc.text(node.value);
        }
        if (node.children.isEmpty()) {
            return Doc.text(node.value + "()");
        }
        List<Doc> parts = new ArrayList<>();
        parts.add(Doc.softline());
        for (int i = 0; i < node.children.size(); i++) {
            if (i > 0) {
                parts.add(Doc.concat(Doc.text(","), Doc.line()));
            }
            parts.add(layout(node.children.get(i)));
        }
        return Doc.group(Doc.concat(
                Doc.text(node.value + "("),
                Doc.indent(Doc.concat(parts)),
                Doc.text(")")));
    }
}
