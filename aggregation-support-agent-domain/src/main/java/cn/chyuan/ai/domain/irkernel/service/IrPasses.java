package cn.chyuan.ai.domain.irkernel.service;

import cn.chyuan.ai.domain.irkernel.service.Ir.Block;
import cn.chyuan.ai.domain.irkernel.service.Ir.Inst;
import cn.chyuan.ai.domain.irkernel.service.Ir.Opcode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 优化 pass 与管线（工单 0840 CU3/0841 CU4/0843 CU6，llvm 思想）。
 * 常量折叠（除零保守不折）/DCE 活引用标记副作用保留/pass 管线注册顺序执行统计与幂等重跑。
 */
public final class IrPasses {

    /** pass 统计 */
    public record PassStat(String name, int before, int after) {
    }

    /** 常量折叠：两源均由 CONST 定义则折为 CONST；除零保守不折 */
    public static int foldConstants(Ir ir) {
        Map<Integer, Long> constants = new HashMap<>();
        for (Block b : ir.blocks.values()) {
            for (Inst inst : b.insts) {
                if (inst.opcode == Opcode.CONST) {
                    constants.put(inst.dest, inst.constValue);
                }
            }
        }
        int folded = 0;
        for (Block b : ir.blocks.values()) {
            List<Inst> rewritten = new ArrayList<>();
            for (Inst inst : b.insts) {
                if (inst.dest >= 0 && (inst.opcode == Opcode.ADD || inst.opcode == Opcode.SUB
                        || inst.opcode == Opcode.MUL || inst.opcode == Opcode.DIV)
                        && constants.containsKey(inst.srcs.get(0)) && constants.containsKey(inst.srcs.get(1))) {
                    long a = constants.get(inst.srcs.get(0));
                    long c = constants.get(inst.srcs.get(1));
                    if (inst.opcode == Opcode.DIV && c == 0) {
                        rewritten.add(inst);
                        continue;
                    }
                    long v = switch (inst.opcode) {
                        case ADD -> a + c;
                        case SUB -> a - c;
                        case MUL -> a * c;
                        default -> a / c;
                    };
                    Inst constInst = new Inst(Opcode.CONST);
                    constInst.dest = inst.dest;
                    constInst.constValue = v;
                    constants.put(inst.dest, v);
                    rewritten.add(constInst);
                    folded++;
                    continue;
                }
                rewritten.add(inst);
            }
            b.insts.clear();
            b.insts.addAll(rewritten);
        }
        return folded;
    }

    /** DCE：自 RET/PRINT 反向标活引用链，删除未标活且无副作用指令 */
    public static int eliminateDeadCode(Ir ir) {
        List<Inst> linear = ir.linear();
        Set<Integer> marked = new LinkedHashSet<>();
        int removed = 0;
        for (int i = linear.size() - 1; i >= 0; i--) {
            Inst inst = linear.get(i);
            boolean needed = inst.hasSideEffect() || inst.opcode == Opcode.RET
                    || (inst.dest >= 0 && marked.contains(inst.dest));
            if (needed) {
                if (inst.dest >= 0) {
                    marked.add(inst.dest);
                }
                marked.addAll(inst.srcs);
            }
        }
        for (Block b : ir.blocks.values()) {
            List<Inst> kept = new ArrayList<>();
            for (Inst inst : b.insts) {
                boolean keep = inst.isTerminator() || inst.hasSideEffect()
                        || inst.dest < 0 || marked.contains(inst.dest);
                if (keep) {
                    kept.add(inst);
                } else {
                    removed++;
                }
            }
            b.insts.clear();
            b.insts.addAll(kept);
        }
        return removed;
    }

    /** 寄存器池映射（供线性扫描使用） */
    public static Map<Integer, Integer> usedRegisters(Ir ir) {
        Map<Integer, Integer> lastUse = new HashMap<>();
        List<Inst> linear = ir.linear();
        for (int i = 0; i < linear.size(); i++) {
            Inst inst = linear.get(i);
            for (int src : inst.srcs) {
                lastUse.put(src, i);
            }
            if (inst.dest >= 0) {
                lastUse.putIfAbsent(inst.dest, i);
            }
        }
        return lastUse;
    }

    /** pass 管线：注册顺序执行 + 统计 + 幂等 */
    public static final class Pipeline {
        private final List<String> names = new ArrayList<>();
        private final Map<String, UnaryOperator<Ir>> passes = new LinkedHashMap<>();

        public Pipeline add(String name, UnaryOperator<Ir> pass) {
            if (passes.containsKey(name)) {
                throw new IllegalArgumentException("重复 pass: " + name);
            }
            names.add(name);
            passes.put(name, pass);
            return this;
        }

        public List<PassStat> run(Ir ir) {
            List<PassStat> stats = new ArrayList<>();
            for (String name : names) {
                int before = ir.instructions();
                passes.get(name).apply(ir);
                stats.add(new PassStat(name, before, ir.instructions()));
            }
            return stats;
        }
    }
}
