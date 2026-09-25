package cn.chyuan.ai.domain.vmkernel.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字节码虚拟机内核测试（工单 0801-0808 CQ1-CQ8，cpython 思想）。
 * 词法 AST/常量池去重与回填/栈帧求值/控制流 break·continue/调用递归深度/异常表跨帧传播/引用计数与循环回收/端口编排。
 */
class VmKernelTest {

    private final VmPort port = VmPort.inMemory();

    @Test
    void lexerTokensAndErrors() {
        var tokens = new Lexer("x = 1 + \"a\";").lex();
        assertEquals(Lexer.Kind.IDENT, tokens.get(0).kind());
        assertEquals(Lexer.Kind.OP, tokens.get(1).kind());
        assertEquals(Lexer.Kind.INT, tokens.get(2).kind());
        assertEquals(Lexer.Kind.STRING, tokens.get(4).kind());
        assertEquals(Lexer.Kind.EOF, tokens.get(tokens.size() - 1).kind());
        var located = assertThrows(IllegalArgumentException.class, () -> new Lexer("a = 1; @").lex());
        assertTrue(located.getMessage().contains("@"), "非法字符报行列定位");
        assertThrows(IllegalArgumentException.class, () -> new Lexer("s = \"open").lex(), "字符串未闭合");
    }

    @Test
    void parserAstAndResidue() {
        var ast = new Parser("x = 1; if (x) { y = 2; } else { y = 3; }").parseProgram();
        assertEquals(2, ast.size());
        assertInstanceOf(Parser.If.class, ast.get(1));
        assertThrows(IllegalArgumentException.class,
                () -> new Parser("x = 1 y = 2;").parseProgram(), "缺分隔残词拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new Compiler().compile(new Parser("break;").parseProgram()), "break 出循环外编译期拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new Parser("try { x = 1; }").parseProgram(), "try 无 catch/finally 拒绝");
    }

    @Test
    void constPoolAndNameDedup() {
        Compiler.Program p = port.compile("s = \"a\"; t = \"a\"; n = 1; m = 1;");
        long aCount = p.constants().stream().filter("a"::equals).count();
        long oneCount = p.constants().stream().filter(v -> Long.valueOf(1L).equals(v)).count();
        assertEquals(1, aCount, "常量池去重");
        assertEquals(1, oneCount);
        assertEquals(4, p.names().size(), "名字表 s/t/n/m");
        assertEquals(4, p.main.ops.stream().filter(op -> op.opcode() == Compiler.Opcode.STORE).count());
    }

    @Test
    void arithmeticAndPrecedence() {
        assertEquals(7L, port.runSource("1 + 2 * 3;"));
        assertEquals(3L, port.runSource("7 / 2;"));
        assertEquals(2L, port.runSource("5 % 3;"));
        assertEquals("a1", port.runSource("\"a\" + 1;"));
        assertEquals(-3L, port.runSource("-3;"));
        assertEquals(Boolean.TRUE, port.runSource("!(1 > 2);"));
        assertEquals(Boolean.TRUE, port.runSource("1 < 2 && 2 <= 2;"));
        assertEquals(Boolean.TRUE, port.runSource("\"b\" > \"a\" || false;"));
        Vm.VmError div = assertThrows(Vm.VmError.class, () -> port.runSource("1 / 0;"), "除零未捕获上抛");
        assertTrue(div.getMessage().contains("division by zero"));
    }

    @Test
    void controlFlowWhileBreakContinue() {
        Object total = port.runSource("""
                total = 0;
                i = 0;
                while (i < 10) {
                  i = i + 1;
                  if (i % 2 == 0) { continue; }
                  if (i > 7) { break; }
                  total = total + i;
                }
                total;
                """);
        assertEquals(16L, total, "奇数累加 1+3+5+7，i>7 断开");
        assertEquals(15L, port.runSource("n = 0; s = 0; while (n < 5) { n = n + 1; s = s + n; } s;"));
    }

    @Test
    void callsAndRecursion() {
        assertEquals(720L, port.runSource("""
                def fact(n) {
                  if (n <= 1) { return 1; }
                  return n * fact(n - 1);
                }
                fact(6);
                """));
        assertEquals(55L, port.runSource("""
                def fib(n) {
                  if (n < 2) { return n; }
                  return fib(n - 1) + fib(n - 2);
                }
                fib(10);
                """));
        assertThrows(Vm.VmError.class, () -> port.runSource("def f() { return f(); } f();"),
                "递归超深度上抛");
        Vm.VmError undef = assertThrows(Vm.VmError.class, () -> port.runSource("nope(1);"), "未定义函数");
        assertTrue(undef.getMessage().contains("未定义函数"));
    }

    @Test
    void exceptionCatchAndCrossFrame() {
        assertEquals("division by zero", port.runSource("""
                caught = "";
                try { v = 1 / 0; } catch (e) { caught = e; }
                caught;
                """), "运行时错误被 try 表捕获");
        assertEquals("boom", port.runSource("""
                caught = "";
                def thrower() { throw "boom"; }
                try { thrower(); } catch (e) { caught = e; }
                caught;
                """), "异常跨调用帧传播被捕获");
        Vm.VmError uncaught = assertThrows(Vm.VmError.class,
                () -> port.runSource("throw \"fatal\";"), "未捕获抛宿主");
        assertEquals("fatal", uncaught.getMessage());
    }

    @Test
    void tryFinallyRethrows() {
        Map<String, Object> globals = new HashMap<>();
        Compiler.Program p = port.compile("""
                try { throw "x"; } finally { y = 2; }
                """);
        Vm.VmError err = assertThrows(Vm.VmError.class, () -> new Vm(p, globals).run(), "finally 后重抛");
        assertEquals("x", err.getMessage());
        assertEquals(2L, globals.get("y"), "finally 体已执行");
        assertEquals("done", port.runSource("""
                log = 0;
                caught = "";
                try {
                  try { throw "boom"; } finally { log = 1; }
                } catch (e) { caught = e; }
                if (log == 1 && caught == "boom") { "done"; } else { "bad"; }
                """), "内层 finally 执行后外层捕获");
    }

    @Test
    void unknownOperandsRejected() {
        Vm.VmError name = assertThrows(Vm.VmError.class, () -> port.runSource("x = missing;"), "未定义名字");
        assertTrue(name.getMessage().contains("is not defined"));
        assertThrows(IllegalArgumentException.class,
                () -> new Parser("x = ;").parseProgram(), "残词拒绝");
    }

    @Test
    void refCountingImmediateReclaim() {
        RefHeap heap = new RefHeap();
        RefHeap.Obj a = heap.alloc("a");
        heap.incref(a);
        heap.decref(a);
        assertEquals(1, heap.refcountOf(a));
        heap.decref(a);
        assertEquals(1, heap.freedCount(), "归零即时回收");
        assertThrows(IllegalStateException.class, () -> heap.incref(a), "已回收不可用");
        RefHeap.Obj box = heap.alloc("box");
        RefHeap.Obj child = heap.link(box, heap.alloc("c"));
        heap.decref(box);
        assertEquals(2, heap.freedCount(), "box 回收并解除对 child 引用");
        assertEquals(1, heap.aliveCount(), "child 自持引用仍在");
        heap.decref(child);
        assertEquals(3, heap.freedCount());
        assertEquals(0, heap.aliveCount());
    }

    @Test
    void cycleIsolationByMarkSweep() {
        RefHeap heap = new RefHeap();
        RefHeap.Obj root = heap.alloc("root");
        RefHeap.Obj a = heap.alloc("a");
        RefHeap.Obj b = heap.alloc("b");
        heap.link(a, b);
        heap.link(b, a);
        int collected = heap.collectCycles(Set.of(root));
        assertEquals(2, collected, "互引环被标记清除回收");
        assertEquals(2, heap.freedCount());
        assertEquals(1, heap.aliveCount(), "可达根存活");
    }

    @Test
    void portStageScriptBinding() {
        assertEquals(42L, port.runStage("return base * 2;", Map.of("base", 21L)),
                "mlkernel 阶段参数脚本变量绑定形态");
        assertEquals("ok", port.runStage("""
                if (threshold > 10) { return "ok"; } else { return "low"; }
                """, Map.of("threshold", 42L)));
        Compiler.Program program = port.compile("6 * 7;");
        assertEquals(42L, port.run(program));
        assertEquals(42L, port.run(program), "同一程序可重复运行");
    }
}
