package cn.chyuan.ai.domain.irkernel.service;

import cn.chyuan.ai.domain.irkernel.service.Ir.Inst;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 线性扫描寄存器分配（工单 0842 CU5，llvm/线性扫描思想）。
 * 活跃区间按起点排序/寄存器池顺序分配/重叠时溢出最远终点者/分配与溢出结果可查。
 */
public final class RegAlloc {

    /** 分配结果：虚拟寄存器 → 物理寄存器编号（-1 表示溢出槽） */
    public record Result(Map<Integer, Integer> allocation, Map<Integer, Integer> spillSlots, int poolSize) {
        public boolean isSpilled(int vreg) {
            return allocation.get(vreg) != null && allocation.get(vreg) < 0;
        }
    }

    /** 活跃区间：起点终点为线性指令编号 */
    public record Interval(int vreg, int start, int end) {
    }

    public static List<Interval> intervals(Ir ir) {
        List<Inst> linear = ir.linear();
        Map<Integer, Integer> start = new HashMap<>();
        Map<Integer, Integer> end = new HashMap<>();
        for (int i = 0; i < linear.size(); i++) {
            Inst inst = linear.get(i);
            if (inst.dest >= 0) {
                start.putIfAbsent(inst.dest, i);
                end.put(inst.dest, i);
            }
            for (int src : inst.srcs) {
                end.put(src, i);
            }
        }
        List<Interval> out = new ArrayList<>();
        for (int reg : new TreeMap<>(start).keySet()) {
            out.add(new Interval(reg, start.get(reg), end.getOrDefault(reg, start.get(reg))));
        }
        out.sort(Comparator.comparingInt(Interval::start).thenComparingInt(Interval::vreg));
        return out;
    }

    public static Result allocate(Ir ir, int poolSize) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("寄存器池须为正");
        }
        List<Interval> intervals = intervals(ir);
        Map<Integer, Integer> allocation = new LinkedHashMap<>();
        Map<Integer, Integer> spillSlots = new HashMap<>();
        java.util.TreeSet<Integer> free = new java.util.TreeSet<>();
        for (int p = 0; p < poolSize; p++) {
            free.add(p);
        }
        List<Interval> active = new ArrayList<>();
        int nextSpill = 0;
        for (Interval cur : intervals) {
            var it = active.iterator();
            while (it.hasNext()) {
                Interval iv = it.next();
                if (iv.end() < cur.start()) {
                    it.remove();
                    free.add(allocation.remove(iv.vreg()));
                }
            }
            if (!free.isEmpty()) {
                int phys = free.pollFirst();
                allocation.put(cur.vreg(), phys);
                active.add(cur);
            } else {
                Interval farthest = active.get(0);
                for (Interval iv : active) {
                    if (iv.end() > farthest.end()) {
                        farthest = iv;
                    }
                }
                if (farthest.end() > cur.end()) {
                    int phys = allocation.remove(farthest.vreg());
                    allocation.put(farthest.vreg(), -1);
                    spillSlots.put(farthest.vreg(), nextSpill++);
                    allocation.put(cur.vreg(), phys);
                    active.remove(farthest);
                    active.add(cur);
                } else {
                    allocation.put(cur.vreg(), -1);
                    spillSlots.put(cur.vreg(), nextSpill++);
                }
            }
        }
        return new Result(Map.copyOf(allocation), Map.copyOf(spillSlots), poolSize);
    }
}
