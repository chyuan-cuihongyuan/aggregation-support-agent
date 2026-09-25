package cn.chyuan.ai.domain.vmkernel.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 引用计数对象模型（工单 0807 CQ7，cpython 内存管理思想）。
 * 引用计数增减/归零即时回收（递归解除容器引用）/容器嵌套/循环引用代际隔离（标记清除模拟回收）。
 */
public final class RefHeap {

    /** 受管对象：引用计数与容器引用边 */
    public static final class Obj {
        int refcount;
        final List<Obj> refs = new ArrayList<>();
        final Object data;
        boolean freed;

        Obj(Object data) {
            this.data = data;
        }
    }

    private final List<Obj> all = new ArrayList<>();
    private int freedCount = 0;

    public Obj alloc(Object data) {
        Obj o = new Obj(data);
        o.refcount = 1;
        all.add(o);
        return o;
    }

    /** 容器持有子对象：建立引用边并 +1 */
    public Obj link(Obj container, Obj child) {
        ensureAlive(container);
        ensureAlive(child);
        child.refcount++;
        container.refs.add(child);
        return child;
    }

    public void incref(Obj o) {
        ensureAlive(o);
        o.refcount++;
    }

    /** 减引用：归零即时回收并递归解除容器引用 */
    public void decref(Obj o) {
        ensureAlive(o);
        o.refcount--;
        if (o.refcount == 0) {
            free(o);
        }
    }

    private void free(Obj o) {
        o.freed = true;
        freedCount++;
        for (Obj child : o.refs) {
            if (!child.freed) {
                child.refcount--;
                if (child.refcount == 0) {
                    free(child);
                }
            }
        }
        o.refs.clear();
    }

    private void ensureAlive(Obj o) {
        if (o.freed) {
            throw new IllegalStateException("已回收对象不可用");
        }
    }

    /** 循环引用隔离：从根集合标记可达，未标记未回收者强制回收（标记清除模拟），返回本轮回收数 */
    public int collectCycles(Set<Obj> roots) {
        Set<Obj> marked = new HashSet<>();
        List<Obj> work = new ArrayList<>(roots);
        while (!work.isEmpty()) {
            Obj cur = work.remove(work.size() - 1);
            if (cur.freed || !marked.add(cur)) {
                continue;
            }
            work.addAll(cur.refs);
        }
        int collected = 0;
        for (Obj o : List.copyOf(all)) {
            if (!o.freed && !marked.contains(o)) {
                o.freed = true;
                freedCount++;
                collected++;
                o.refs.clear();
            }
        }
        return collected;
    }

    public int freedCount() {
        return freedCount;
    }

    public int aliveCount() {
        return (int) all.stream().filter(o -> !o.freed).count();
    }

    public int refcountOf(Obj o) {
        return o.refcount;
    }
}
