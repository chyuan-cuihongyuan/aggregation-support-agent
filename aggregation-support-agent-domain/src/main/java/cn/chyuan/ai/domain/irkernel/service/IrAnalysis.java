package cn.chyuan.ai.domain.irkernel.service;

import cn.chyuan.ai.domain.irkernel.service.Ir.Block;
import cn.chyuan.ai.domain.irkernel.service.Ir.Inst;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IR 分析（工单 0838 CU1/0839 CU2，llvm 思想）。
 * defs-use 链与单赋值校验/支配树迭代计算/支配边界与 φ 插入点（多前驱汇合块）识别。
 */
public final class IrAnalysis {

    /** 寄存器 def-use：defs 应为 1（SSA），uses 为使用指令清单 */
    public record DefUse(int reg, List<Inst> defs, List<Inst> uses) {
        public boolean isSsa() {
            return defs.size() <= 1;
        }
    }

    public static Map<Integer, DefUse> defUse(Ir ir) {
        Map<Integer, List<Inst>> defs = new HashMap<>();
        Map<Integer, List<Inst>> uses = new HashMap<>();
        for (Block b : ir.blocks.values()) {
            for (Inst inst : b.insts) {
                if (inst.dest >= 0) {
                    defs.computeIfAbsent(inst.dest, k -> new ArrayList<>()).add(inst);
                }
                for (int src : inst.srcs) {
                    uses.computeIfAbsent(src, k -> new ArrayList<>()).add(inst);
                }
            }
        }
        Map<Integer, DefUse> out = new HashMap<>();
        Set<Integer> all = new LinkedHashSet<>();
        all.addAll(defs.keySet());
        all.addAll(uses.keySet());
        for (int reg : all) {
            out.put(reg, new DefUse(reg, defs.getOrDefault(reg, List.of()), uses.getOrDefault(reg, List.of())));
        }
        return out;
    }

    /** 支配集合：迭代数据流（entry 支配自身；其余初始为全集收缩） */
    public static Map<Integer, Set<Integer>> dominators(Ir ir) {
        int entry = ir.entry();
        List<Integer> order = new ArrayList<>(ir.blockOrder);
        Map<Integer, Set<Integer>> dom = new HashMap<>();
        for (int id : order) {
            dom.put(id, id == entry ? new LinkedHashSet<>(List.of(entry)) : new LinkedHashSet<>(order));
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int id : order) {
                if (id == entry) {
                    continue;
                }
                Set<Integer> next = null;
                for (Block b : ir.blocks.values()) {
                    if (b.successors.contains(id)) {
                        Set<Integer> predDom = dom.get(b.id);
                        if (next == null) {
                            next = new LinkedHashSet<>(predDom);
                        } else {
                            next.retainAll(predDom);
                        }
                    }
                }
                if (next == null) {
                    next = new LinkedHashSet<>(List.of(id));
                }
                next.add(id);
                if (!next.equals(dom.get(id))) {
                    dom.put(id, next);
                    changed = true;
                }
            }
        }
        return dom;
    }

    /** 直接支配者：支配集合中除自身外最后一个支配者 */
    public static Map<Integer, Integer> idom(Ir ir) {
        Map<Integer, Set<Integer>> dom = dominators(ir);
        int entry = ir.entry();
        Map<Integer, Integer> out = new HashMap<>();
        for (int id : ir.blockOrder) {
            if (id == entry) {
                continue;
            }
            Set<Integer> candidates = new LinkedHashSet<>(dom.get(id));
            candidates.remove(id);
            int best = entry;
            int bestSize = -1;
            for (int c : candidates) {
                if (dom.get(c).size() > bestSize) {
                    bestSize = dom.get(c).size();
                    best = c;
                }
            }
            out.put(id, best);
        }
        return out;
    }

    /** φ 插入点：多前驱汇合块（工单 0839 CU2 简化口径） */
    public static List<Integer> phiInsertionPoints(Ir ir) {
        Map<Integer, Integer> predCount = new HashMap<>();
        for (Block b : ir.blocks.values()) {
            for (int succ : new ArrayList<>(b.successors)) {
                predCount.merge(succ, 1, Integer::sum);
            }
        }
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : predCount.entrySet()) {
            if (e.getValue() > 1) {
                out.add(e.getKey());
            }
        }
        out.sort(Integer::compareTo);
        return out;
    }

    /** 深度优先可达块（死块分析备用） */
    public static Set<Integer> reachable(Ir ir) {
        Set<Integer> seen = new LinkedHashSet<>();
        Deque<Integer> work = new ArrayDeque<>();
        work.push(ir.entry());
        while (!work.isEmpty()) {
            int id = work.pop();
            if (!seen.add(id)) {
                continue;
            }
            Block b = ir.block(id);
            if (b != null) {
                work.addAll(b.successors);
            }
        }
        return seen;
    }
}
