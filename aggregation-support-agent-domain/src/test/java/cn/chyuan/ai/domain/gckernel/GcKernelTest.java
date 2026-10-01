package cn.chyuan.ai.domain.gckernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 垃圾回收内核测试（工单 1228-1235 FI1-FI8，go 思想）。
 * 堆分配/对象图/三色标记/写屏障/清扫/触发步调/STW 阶段/端口组合管线。
 */
class GcKernelTest {

    @Test
    void heapAlloc() {
        GcPort port = GcPort.inMemory();
        int first = port.alloc(8);
        int second = port.alloc(4);
        assertNotEquals(first, second, "对象 id 唯一");
        assertEquals(12, port.usedBytes(), "堆使用记账");
        assertEquals(12, port.totalAllocated(), "累计分配记账");
        assertTrue(port.alive(first));
        assertThrows(IllegalArgumentException.class, () -> port.alloc(0), "零大小拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.alloc(-1), "负大小拒绝");
    }

    @Test
    void objectGraph() {
        GcPort port = GcPort.inMemory();
        int a = port.alloc(8);
        int b = port.alloc(8);
        port.refer(a, b);
        port.refer(a, a);
        port.root(a);
        port.root(a);
        port.unroot(a);
        port.root(a);
        assertThrows(IllegalArgumentException.class, () -> port.refer(a, 999), "未知目标引用拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.refer(999, a), "未知来源引用拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.root(999), "未知对象根声明拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.cut(b, a), "未知边删除拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.unroot(999), "未知对象取消根拒绝");
    }

    @Test
    void triMark() {
        GcPort port = GcPort.inMemory();
        int a = port.alloc(8);
        int b = port.alloc(8);
        int c = port.alloc(8);
        int orphan = port.alloc(8);
        port.root(a);
        port.refer(a, b);
        port.refer(b, c);
        port.gc();
        assertTrue(port.alive(a) && port.alive(b) && port.alive(c), "从根可达全存活");
        assertFalse(port.alive(orphan), "不可达留白被回收");
        assertEquals(1, port.sweptTotal());
        assertEquals(24, port.usedBytes(), "清扫释放堆计数");
        assertEquals(32, port.totalAllocated(), "累计分配不回退");

        GcPort diamond = GcPort.inMemory();
        int r = diamond.alloc(8);
        int x = diamond.alloc(8);
        int y = diamond.alloc(8);
        int leaf = diamond.alloc(8);
        diamond.root(r);
        diamond.refer(r, x);
        diamond.refer(r, y);
        diamond.refer(x, leaf);
        diamond.refer(y, leaf);
        diamond.gc();
        assertTrue(diamond.alive(leaf), "菱形汇合已黑重访幂等不误收");
        assertEquals(0, diamond.sweptTotal());
    }

    @Test
    void writeBarrier() {
        GcPort port = GcPort.inMemory();
        int root = port.alloc(8);
        int mid = port.alloc(8);
        int lateTarget = port.alloc(8);
        port.root(root);
        port.refer(root, mid);
        port.startCycle();
        assertTrue(port.marking(), "进入并发标记");
        port.refer(mid, lateTarget);
        while (port.markStep()) {
            // 推进并发标记
        }
        port.finishCycle();
        assertTrue(port.alive(lateTarget), "标记期新增引用经屏障置灰存活");

        GcPort twoPaths = GcPort.inMemory();
        int r = twoPaths.alloc(8);
        int p1 = twoPaths.alloc(8);
        int p2 = twoPaths.alloc(8);
        int shared = twoPaths.alloc(8);
        twoPaths.root(r);
        twoPaths.refer(r, p1);
        twoPaths.refer(r, p2);
        twoPaths.refer(p1, shared);
        twoPaths.refer(p2, shared);
        twoPaths.startCycle();
        assertTrue(twoPaths.markStep(), "标记根");
        twoPaths.cut(p1, shared);
        while (twoPaths.markStep()) {
            // 继续推进
        }
        twoPaths.finishCycle();
        assertTrue(twoPaths.alive(shared), "标记期删边不致漏标（多路径存活）");
    }

    @Test
    void sweep() {
        GcPort port = GcPort.inMemory();
        int keep = port.alloc(8);
        port.alloc(16);
        port.alloc(32);
        port.root(keep);
        port.gc();
        assertTrue(port.alive(keep));
        assertEquals(2, port.sweptTotal(), "清扫统计");
        assertEquals(8, port.usedBytes());
        assertEquals(56, port.totalAllocated());
        port.root(keep);
        port.gc();
        assertEquals(2, port.sweptTotal(), "全存活零清扫");
    }

    @Test
    void gcTrigger() {
        GcPort port = GcPort.inMemory();
        assertEquals(256, port.threshold(), "初始阈值");
        port.alloc(100);
        port.alloc(100);
        port.alloc(100);
        assertEquals(1, port.cycleCount(), "越过阈值自动触发");
        assertEquals(0, port.usedBytes(), "无根垃圾全回收");
        assertEquals(256, port.threshold(), "存活为零阈值回落下限");

        int rooted = port.alloc(100);
        port.root(rooted);
        port.gc();
        assertEquals(2, port.cycleCount(), "手动触发");
        assertEquals(256, port.threshold(), "阈值=上次存活堆×2，最小堆下限兜底");
        port.alloc(100);
        port.alloc(100);
        assertEquals(3, port.cycleCount(), "300>256 再次自动触发");
        assertTrue(port.alive(rooted), "根对象跨周期存活");
        assertEquals(100, port.usedBytes());
    }

    @Test
    void stwPhases() {
        GcPort port = GcPort.inMemory();
        int id = port.alloc(8);
        port.root(id);
        port.startCycle();
        assertTrue(port.marking(), "STW 准备后进入并发标记");
        while (port.markStep()) {
            // 标记推进
        }
        port.finishCycle();
        assertFalse(port.marking(), "清扫完成后回到空闲");
        assertEquals(1, port.cycleCount());
        assertEquals(2, port.stwCount(), "每周期 STW 两次（准备+终止）");
        assertEquals(6, port.stwTicks(), "STW 时长虚拟计时");
        port.gc();
        assertEquals(2, port.cycleCount());
        assertEquals(4, port.stwCount());
        assertEquals(12, port.stwTicks());
    }

    @Test
    void gcPipeline() {
        GcPort port = GcPort.inMemory();
        int a = port.alloc(64);
        int b = port.alloc(64);
        port.root(a);
        port.refer(a, b);
        port.alloc(64);
        port.gc();
        assertTrue(port.alive(a) && port.alive(b));
        assertEquals(1, port.cycleCount());
        assertTrue(port.totalAllocated() >= 192);
        assertEquals(128, port.usedBytes());
        assertEquals(256, port.threshold(), "128×2 步调更新");
        assertEquals(List.of("opcode", "arg"), port.heapShape(),
                "vmkernel 堆指令形状只读联动");
    }
}
