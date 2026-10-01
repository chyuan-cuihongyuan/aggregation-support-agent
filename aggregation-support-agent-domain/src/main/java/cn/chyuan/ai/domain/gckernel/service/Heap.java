package cn.chyuan.ai.domain.gckernel.service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 堆与对象图（工单 1228 FI1 / 1229 FI2，go 思想）。
 * 分配记账（id 唯一、使用字节累计、非法大小拒绝）；引用边登记与删除；
 * 根集合声明幂等；未知对象引用/根声明拒绝。
 */
public final class Heap {

    /** 堆对象：id 唯一、size 记账 */
    public record Obj(int id, int size) {
    }

    private final Map<Integer, Obj> objects = new LinkedHashMap<>();
    private final Map<Integer, Set<Integer>> refs = new HashMap<>();
    private final Set<Integer> roots = new LinkedHashSet<>();
    private long usedBytes;
    private long totalAllocated;
    private int seq;

    /** 分配：非法大小拒绝 */
    public Obj alloc(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("分配大小须为正: " + size);
        }
        Obj obj = new Obj(++seq, size);
        objects.put(obj.id(), obj);
        refs.put(obj.id(), new LinkedHashSet<>());
        usedBytes += size;
        totalAllocated += size;
        return obj;
    }

    /** 引用边；两端未知拒绝；自环允许 */
    public void refer(int from, int to) {
        require(from);
        require(to);
        refs.get(from).add(to);
    }

    /** 删边；未知边拒绝 */
    public void cut(int from, int to) {
        require(from);
        require(to);
        if (!refs.get(from).remove(to)) {
            throw new IllegalArgumentException("未知引用边: " + from + "->" + to);
        }
    }

    /** 根声明；未知对象拒绝；重复幂等 */
    public void root(int id) {
        require(id);
        roots.add(id);
    }

    /** 取消根；未知对象拒绝 */
    public void unroot(int id) {
        require(id);
        roots.remove(id);
    }

    public boolean alive(int id) {
        return objects.containsKey(id);
    }

    public Obj obj(int id) {
        require(id);
        return objects.get(id);
    }

    public Set<Integer> refIds(int id) {
        require(id);
        return refs.get(id);
    }

    public Set<Integer> rootIds() {
        return Set.copyOf(roots);
    }

    /** 回收：移除对象与其出边、其他对象指向它的入边，并释放使用字节记账 */
    void free(int id) {
        Obj obj = objects.remove(id);
        if (obj != null) {
            usedBytes -= obj.size();
        }
        refs.remove(id);
        roots.remove(id);
        for (Set<Integer> out : refs.values()) {
            out.remove(id);
        }
    }

    /** 存活对象 id（分配序） */
    public java.util.List<Integer> aliveIds() {
        return new java.util.ArrayList<>(objects.keySet());
    }

    public long usedBytes() {
        return usedBytes;
    }

    public long totalAllocated() {
        return totalAllocated;
    }

    private void require(int id) {
        if (!objects.containsKey(id)) {
            throw new IllegalArgumentException("未知对象: " + id);
        }
    }
}
