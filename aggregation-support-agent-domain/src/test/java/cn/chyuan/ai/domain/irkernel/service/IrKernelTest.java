package cn.chyuan.ai.domain.irkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IR 遍历内核测试（工单 0838-0845 CU1-CU8，llvm 思想）。
 * SSA 单赋值/支配树与 φ 插入点/常量折叠除零保守/DCE 链式标活/线性扫描溢出/管线统计幂等/打印往返/端口编排。
 */
class IrKernelTest {

    @Test
    void ssaBuildAndDefUse() {
        Ir.Builder b = new Ir.Builder();
        int a = b.constOf(2);
        int c = b.constOf(3);
        int sum = b.binOp(Ir.Opcode.ADD, a, c);
        b.ret(sum);
        var defUse = IrAnalysis.defUse(b.ir);
        assertTrue(defUse.get(sum).isSsa(), "单赋值");
        assertEquals(1, defUse.get(a).defs().size());
        assertEquals(1, defUse.get(a).uses().size());
        assertEquals(0, a);
        assertEquals(1, c, "每次产出新版本寄存器");
        assertEquals(3, b.ir.vregs());
    }

    @Test
    void dominatorsOnDiamond() {
        Ir.Builder b = new Ir.Builder();
        int cond = b.constOf(1);
        int thenBlock = b.ir.newBlock().id;
        int v1 = b.constOf(10);
        int elseBlock = b.ir.newBlock().id;
        int v2 = b.constOf(20);
        int join = b.ir.newBlock().id;
        b.current = b.ir.block(0);
        b.branch(cond, thenBlock, elseBlock);
        b.current = b.ir.block(thenBlock);
        b.jump(join);
        b.current = b.ir.block(elseBlock);
        b.jump(join);
        b.current = b.ir.block(join);
        b.ret(v1);
        var dom = IrAnalysis.dominators(b.ir);
        assertTrue(dom.get(join).contains(0), "入口支配汇合块");
        assertFalse(dom.get(join).contains(thenBlock), "then 不支配汇合块");
        assertEquals(0, IrAnalysis.idom(b.ir).get(join), "直接支配者为入口");
        assertEquals(List.of(join), IrAnalysis.phiInsertionPoints(b.ir), "汇合块即 φ 插入点");
        Ir.Builder line = new Ir.Builder();
        line.ret(line.constOf(1));
        assertTrue(IrAnalysis.phiInsertionPoints(line.ir).isEmpty(), "直线无 φ 插入点");
    }

    @Test
    void constFolding() {
        Ir.Builder b = new Ir.Builder();
        int two = b.constOf(2);
        int three = b.constOf(3);
        int sum = b.binOp(Ir.Opcode.ADD, two, three);
        int four = b.constOf(4);
        int prod = b.binOp(Ir.Opcode.MUL, sum, four);
        b.ret(prod);
        int folded = IrPasses.foldConstants(b.ir);
        assertEquals(2, folded, "2+3 与传递 *4");
        Ir inst = b.ir;
        assertEquals(20L, inst.block(0).insts.stream()
                .filter(i -> i.dest == prod).findFirst().orElseThrow().constValue);
    }

    @Test
    void foldDivZeroConservative() {
        Ir.Builder b = new Ir.Builder();
        int one = b.constOf(1);
        int zero = b.constOf(0);
        int div = b.binOp(Ir.Opcode.DIV, one, zero);
        b.ret(div);
        assertEquals(0, IrPasses.foldConstants(b.ir), "除零保守不折");
        assertEquals(4, b.ir.instructions(), "指令保留原样");
    }

    @Test
    void dceRemovesUnusedKeepsSideEffect() {
        Ir.Builder b = new Ir.Builder();
        int used = b.constOf(7);
        int dead1 = b.constOf(1);
        int dead2 = b.binOp(Ir.Opcode.ADD, dead1, dead1);
        int side = b.constOf(9);
        b.print(side);
        b.ret(used);
        int removed = IrPasses.eliminateDeadCode(b.ir);
        assertEquals(2, removed, "dead1/dead2 链删除");
        assertTrue(b.ir.linear().stream().anyMatch(i -> i.opcode == Ir.Opcode.PRINT), "副作用保留");
        assertTrue(b.ir.linear().stream().anyMatch(i -> i.opcode == Ir.Opcode.CONST && i.constValue == 7));
        assertTrue(b.ir.linear().stream().anyMatch(i -> i.opcode == Ir.Opcode.CONST && i.constValue == 9),
                "print 的源被标活");
        assertEquals(0, IrPasses.eliminateDeadCode(b.ir), "幂等重跑");
    }

    @Test
    void linearScanAllocateAndSpill() {
        Ir.Builder b = new Ir.Builder();
        int a = b.constOf(1);
        int c = b.constOf(2);
        int s1 = b.binOp(Ir.Opcode.ADD, a, c);
        int d = b.constOf(3);
        int s2 = b.binOp(Ir.Opcode.MUL, s1, d);
        b.ret(s2);
        var result = RegAlloc.allocate(b.ir, 2);
        assertEquals(2, result.poolSize());
        for (int vreg : result.allocation().keySet()) {
            int phys = result.allocation().get(vreg);
            assertTrue(phys >= -1 && phys < 2, "分配在池内或溢出");
        }
        assertFalse(result.spillSlots().isEmpty(), "4 活跃区间 2 池必有溢出");
        var noSpill = RegAlloc.allocate(b.ir, 8);
        assertTrue(noSpill.spillSlots().isEmpty(), "大池无溢出");
        assertThrows(IllegalArgumentException.class, () -> RegAlloc.allocate(b.ir, 0), "非法池拒绝");
    }

    @Test
    void pipelineStatsIdempotent() {
        Ir.Builder b = new Ir.Builder();
        int dead = b.constOf(1);
        int used = b.constOf(2);
        int three = b.constOf(3);
        int sum = b.binOp(Ir.Opcode.ADD, used, three);
        int unusedFold = b.binOp(Ir.Opcode.ADD, dead, dead);
        b.ret(sum);
        IrPasses.Pipeline pipeline = new IrPasses.Pipeline()
                .add("fold", x -> {
                    IrPasses.foldConstants(x);
                    return x;
                })
                .add("dce", x -> {
                    IrPasses.eliminateDeadCode(x);
                    return x;
                });
        var stats = pipeline.run(b.ir);
        assertEquals(2, stats.size());
        assertEquals(stats.get(0).before(), stats.get(0).after(), "折叠原位替换不减指令数");
        assertTrue(stats.get(1).after() < stats.get(1).before(), "DCE 减少指令");
        int after = b.ir.instructions();
        pipeline.run(b.ir);
        assertEquals(after, b.ir.instructions(), "幂等重跑不再变化");
    }

    @Test
    void printParseRoundTrip() {
        Ir.Builder b = new Ir.Builder();
        int a = b.constOf(5);
        int c = b.constOf(6);
        int sum = b.binOp(Ir.Opcode.ADD, a, c);
        b.print(sum);
        b.ret(sum);
        String text = b.ir.print();
        Ir reparsed = Ir.parse(text);
        assertEquals(text, reparsed.print(), "往返一致");
    }

    @Test
    void parseInvalidRejected() {
        assertThrows(IllegalArgumentException.class, () -> Ir.parse("%0 = frob %1, %2"), "未知操作拒绝");
        assertThrows(IllegalArgumentException.class, () -> Ir.parse("x0 = const 5"), "非法寄存器拒绝");
        assertThrows(IllegalArgumentException.class, () -> Ir.parse("%0 = const 5"), "块外指令拒绝");
        assertThrows(IllegalArgumentException.class, () -> Ir.parse("b9:\n%0 = const 1"), "块号不连续拒绝");
    }

    @Test
    void portBuildOptimizeRawOps() {
        IrPort port = IrPort.inMemory();
        Ir ir = port.build(List.of(
                new IrPort.RawOp("CONST", 2, 0, 0, 0),
                new IrPort.RawOp("CONST", 3, 1, 0, 0),
                new IrPort.RawOp("ADD", 0, 2, 0, 1),
                new IrPort.RawOp("PRINT", 0, 0, 2, 0),
                new IrPort.RawOp("RET", 0, 0, 2, 0)));
        assertEquals(5, ir.instructions());
        IrPort.OptimizeStats stats = port.optimize(ir, 4);
        assertEquals(5, stats.before());
        assertEquals(3, stats.after(), "折叠 2+3 后 DCE 清除未用 const");
        assertTrue(stats.passes().get(0).name().equals("fold")
                && stats.passes().get(1).name().equals("dce"), "管线顺序 fold→dce");
        assertThrows(IllegalArgumentException.class,
                () -> port.build(List.of(new IrPort.RawOp("FROB", 0, 0, 0, 0))), "未知指令拒绝");
        assertEquals(port.printAndReparse(ir), ir.print(), "端口往返打印一致");
    }
}
