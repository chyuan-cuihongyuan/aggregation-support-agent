package cn.chyuan.ai.domain.irkernel.service;

import java.util.List;
import java.util.Map;

/**
 * IR 端口（工单 0845 CU8，llvm 思想）。
 * build·optimize 入口统一编排/与 vmkernel 字节码指令序列作 IR 输入形态只读联动（RawOp 泛型不 import）/
 * ir-kernel.enabled 默认关（开启才改变行为）。
 */
public interface IrPort {

    /** 原始指令形状（宿主侧适配，形状数据不 import vmkernel）：dest 为宿主寄存器编号，src1/src2 为源 */
    record RawOp(String opcode, long constValue, int dest, int src1, int src2) {
    }

    /** 优化统计 */
    record OptimizeStats(int before, int after, List<IrPasses.PassStat> passes) {
    }

    Ir build(List<RawOp> ops);

    OptimizeStats optimize(Ir ir, int regPool);

    /** 打印即解析（往返） */
    default String printAndReparse(Ir ir) {
        return Ir.parse(ir.print()).print();
    }

    static IrPort inMemory() {
        return new InMemoryIr();
    }
}

final class InMemoryIr implements IrPort {

    @Override
    public Ir build(List<IrPort.RawOp> ops) {
        Ir.Builder builder = new Ir.Builder();
        Map<Integer, Integer> regMap = new java.util.HashMap<>();
        for (IrPort.RawOp op : ops) {
            switch (op.opcode().toUpperCase()) {
                case "CONST" -> regMap.put(op.dest(), builder.constOf(op.constValue()));
                case "ADD", "SUB", "MUL", "DIV", "CMP" -> regMap.put(op.dest(),
                        builder.binOp(Ir.Opcode.valueOf(op.opcode().toUpperCase()),
                                regMap.getOrDefault(op.src1(), 0), regMap.getOrDefault(op.src2(), 0)));
                case "PRINT" -> builder.print(regMap.getOrDefault(op.src1(), 0));
                case "RET" -> builder.ret(regMap.getOrDefault(op.src1(), 0));
                default -> throw new IllegalArgumentException("未知指令: " + op.opcode());
            }
        }
        return builder.ir;
    }

    @Override
    public IrPort.OptimizeStats optimize(Ir ir, int regPool) {
        IrPasses.Pipeline pipeline = new IrPasses.Pipeline()
                .add("fold", unused -> {
                    IrPasses.foldConstants(ir);
                    return ir;
                })
                .add("dce", unused -> {
                    IrPasses.eliminateDeadCode(ir);
                    return ir;
                });
        int before = ir.instructions();
        List<IrPasses.PassStat> stats = pipeline.run(ir);
        return new IrPort.OptimizeStats(before, ir.instructions(), stats);
    }
}
