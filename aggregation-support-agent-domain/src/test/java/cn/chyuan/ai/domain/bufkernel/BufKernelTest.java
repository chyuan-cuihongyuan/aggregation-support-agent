package cn.chyuan.ai.domain.bufkernel.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内存池内核测试（工单 1196-1203 FE1-FE8，netty 思想）。
 * 引用计数/arena 分配/池化归还/切片派生/读写指针/线程本地缓存/泄漏检测/端口组合管线。
 */
class BufKernelTest {

    @Test
    void refCount() {
        BufPort port = BufPort.inMemory();
        int buf = port.allocate(32);
        assertEquals(1, port.refs(buf), "创建即 1 引用");
        port.retain(buf);
        assertEquals(2, port.refs(buf));
        port.release(buf);
        assertEquals(1, port.refs(buf));
        port.release(buf);
        assertEquals(0, port.refs(buf), "归零回收");
        assertThrows(IllegalStateException.class, () -> port.release(buf), "释放超额拒绝");
        assertThrows(IllegalStateException.class, () -> port.retain(buf), "释放后保留拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.refs(999), "未知缓冲拒绝");
    }

    @Test
    void arenaAllocate() {
        PoolArena arena = new PoolArena(64, 2);
        assertEquals(16, arena.sizeClass(10), "向上取 2 的幂下限 16");
        assertEquals(32, arena.sizeClass(17));
        assertThrows(IllegalArgumentException.class, () -> arena.sizeClass(0), "非正尺寸拒绝");
        assertThrows(IllegalArgumentException.class, () -> arena.sizeClass(65), "超最大分级拒绝");
        ByteBuf first = arena.allocate(10);
        ByteBuf second = arena.allocate(17);
        assertEquals(0, first.offset(), "chunk 内偏移单调");
        assertEquals(16, second.offset());
        assertEquals(1, arena.chunkCount());
        ByteBuf third = arena.allocate(64);
        assertEquals(2, arena.chunkCount(), "chunk 不足开新 chunk");
        assertThrows(IllegalStateException.class, () -> arena.allocate(16), "arena 容量耗尽拒绝");
        assertEquals(128, arena.usedBytes());
    }

    @Test
    void poolReturn() {
        BufPort port = BufPort.inMemory();
        int first = port.allocate(32);
        port.release(first);
        int reused = port.allocate(32);
        assertEquals(first, reused, "同尺寸 LIFO 复用");
        int fresh = port.allocate(32);
        assertNotEquals(first, fresh, "池空后新分配");
        port.release(first);
        int otherClass = port.allocate(40);
        assertNotEquals(first, otherClass, "不同分级不复用");
        assertThrows(IllegalStateException.class, () -> port.release(first), "重复归还拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.release(999), "未知归还拒绝");
    }

    @Test
    void sliceDerive() {
        BufPort port = BufPort.inMemory();
        int parent = port.allocate(64);
        port.write(parent, "abcdefghijklmnop".getBytes(StandardCharsets.UTF_8));
        int slice = port.slice(parent, 0, 16);
        assertArrayEquals("abcdefghijklmnop".getBytes(StandardCharsets.UTF_8), port.read(slice, 16),
                "切片共享底层");
        assertThrows(IllegalArgumentException.class, () -> port.slice(parent, -1, 4), "切片越界拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.slice(parent, 0, 999), "切片超长拒绝");
        port.release(parent);
        assertThrows(IllegalStateException.class, () -> port.read(slice, 1), "父释放后切片拒绝读写");

        int p2 = port.allocate(64);
        int retained = port.retainedSlice(p2, 0, 16);
        assertEquals(2, port.refs(p2), "retainedSlice 父引用 +1");
        port.release(p2);
        assertEquals(1, port.refs(p2), "父释放被切片引用顶住");
        port.write(p2, new byte[]{1});
        port.release(retained);
        assertEquals(0, port.refs(p2), "切片释放扣父引用");
        assertThrows(IllegalStateException.class, () -> port.write(p2, new byte[]{1}), "引用归零即回收");

        int p3 = port.allocate(64);
        int shared = port.slice(p3, 0, 8);
        assertThrows(IllegalStateException.class, () -> port.release(shared), "共享切片无独立引用");
    }

    @Test
    void rwPointers() {
        BufPort port = BufPort.inMemory();
        int buf = port.allocate(32);
        assertEquals(0, port.readable(buf), "初始无可读");
        port.write(buf, "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(3, port.readable(buf));
        assertArrayEquals("ab".getBytes(StandardCharsets.UTF_8), port.read(buf, 2), "读指针前移");
        assertEquals(1, port.readable(buf));
        assertThrows(IllegalArgumentException.class, () -> port.read(buf, 2), "越界读拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.write(buf, new byte[30]), "越界写拒绝");
        port.write(buf, new byte[1]);
        assertEquals(2, port.readable(buf), "追加计入可读");
        assertArrayEquals("c".getBytes(StandardCharsets.UTF_8), port.read(buf, 1), "剩余可读续读");
        port.read(buf, 1);
        assertEquals(0, port.readable(buf), "读尽");
    }

    @Test
    void threadCache() {
        BufPort port = BufPort.inMemory();
        int first = port.allocate(32, "t1");
        port.release(first, "t1");
        int hit = port.allocate(32, "t1");
        assertEquals(first, hit, "同线程同尺寸命中缓存");
        int cross = port.allocate(32, "t2");
        assertNotEquals(first, cross, "跨线程走共享池");

        int u1 = port.allocate(32, "w");
        int u2 = port.allocate(32, "w");
        int u3 = port.allocate(32, "w");
        port.release(u1, "w");
        port.release(u2, "w");
        port.release(u3, "w");
        assertEquals(u3, port.allocate(32, "w"), "容量 2 溢出最旧淘汰后 LIFO");
        assertEquals(u2, port.allocate(32, "w"));
        assertEquals(u1, port.allocate(32, "w"), "被挤出者落入共享池仍可复用");
        int brandNew = port.allocate(32, "w");
        assertNotEquals(u1, brandNew);
        assertNotEquals(u2, brandNew);
        assertNotEquals(u3, brandNew);
    }

    @Test
    void leakDetect() {
        BufPort port = BufPort.inMemory();
        int kept = port.allocate(16);
        int freed = port.allocate(16);
        port.release(freed);
        assertEquals(List.of(kept), port.leaks(), "未释放即在泄漏报告");
        assertEquals(List.of(kept), port.leaks(), "重复报告幂等");
        port.release(kept);
        assertEquals(List.of(), port.leaks(), "释放清案");
    }

    @Test
    void bufPipeline() {
        BufPort port = BufPort.inMemory();
        int buf = port.allocate(64, "t1");
        port.write(buf, "hello pool".getBytes(StandardCharsets.UTF_8));
        int view = port.slice(buf, 0, 5);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), port.read(view, 5));
        port.retain(buf);
        port.release(buf, "t1");
        assertEquals(1, port.refs(buf));
        port.release(buf);
        int again = port.allocate(64, "t1");
        assertEquals(buf, again, "释放后同线程复用");
        assertTrue(port.usedBytes() >= 64);
        assertTrue(port.chunks() >= 1);
        port.release(again);
        assertEquals(List.of(), port.leaks(), "全数释放后无泄漏");
        assertEquals(List.of("key", "sequence", "value"), port.segmentShape(),
                "storekernel 段形状只读联动");
    }
}
