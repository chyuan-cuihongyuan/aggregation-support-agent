package cn.chyuan.ai.domain.leasekernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 租约监听内核测试（工单 1180-1187 FC1-FC8，etcd 思想）。
 * KV 修订全序/租约授予/保活/撤销/watch 事件流/事件过滤/压缩/端口组合管线。
 */
class LeaseKernelTest {

    @Test
    void kvRevision() {
        Revision kv = new Revision();
        assertEquals(1, kv.put("a", "1"));
        assertEquals(2, kv.put("b", "2"));
        assertEquals(3, kv.put("a", "1b"));
        Revision.DeleteResult del = kv.delete("a");
        assertEquals(4, del.revision());
        assertTrue(del.existed());
        assertNull(kv.get("a"));
        assertEquals("2", kv.get("b"));
        assertEquals("1", kv.getAt("a", 2), "历史读取修订前版本");
        assertEquals("1b", kv.getAt("a", 3));
        assertEquals("2", kv.getAt("b", 4));
        Revision.DeleteResult absent = kv.delete("ghost");
        assertFalse(absent.existed());
        assertEquals(0, absent.revision(), "不存在的 key 不产生修订");
        assertEquals(4, kv.current(), "删除后修订仍单调");
        assertThrows(IllegalArgumentException.class, () -> kv.put("", "x"), "空 key 拒绝");
        assertThrows(IllegalArgumentException.class, () -> kv.getAt("a", 5), "修订越界拒绝");
        assertThrows(IllegalArgumentException.class, () -> kv.getAt("a", 0), "修订非正拒绝");
    }

    @Test
    void leaseGrant() {
        long[] now = {5};
        Leases leases = new Leases(() -> now[0]);
        long first = leases.grant(10);
        long second = leases.grant(20);
        assertNotEquals(first, second, "leaseId 唯一");
        assertEquals(15, leases.expiry(first), "到期=当前时钟+TTL");
        assertEquals(25, leases.expiry(second));
        leases.attach(first, "k2");
        leases.attach(first, "k1");
        assertEquals(List.of("k1", "k2"), leases.keys(first), "挂靠 key 字典序");
        assertThrows(IllegalArgumentException.class, () -> leases.attach(999, "x"), "未知租约挂靠拒绝");
        assertThrows(IllegalArgumentException.class, () -> leases.grant(0), "非正 TTL 拒绝");
    }

    @Test
    void leaseKeepalive() {
        long[] now = {0};
        Leases leases = new Leases(() -> now[0]);
        long lease = leases.grant(10);
        leases.attach(lease, "cfg");
        now[0] = 6;
        assertEquals(16, leases.keepAlive(lease), "保活按当前时钟刷新到期");
        now[0] = 11;
        assertTrue(leases.reclaimExpired().isEmpty(), "原到期已过但保活未到，不回收");
        assertEquals(List.of("cfg"), leases.keys(lease));
        now[0] = 21;
        assertEquals(List.of("cfg"), leases.reclaimExpired(), "到期回收连带挂靠 key");
        assertTrue(leases.reclaimed(lease), "回收留痕");
        assertThrows(IllegalArgumentException.class, () -> leases.keys(lease), "回收后租约注销");
        assertThrows(IllegalArgumentException.class, () -> leases.keepAlive(lease), "回收后保活拒绝");
        assertThrows(IllegalArgumentException.class, () -> leases.keepAlive(999), "未知租约保活拒绝");
    }

    @Test
    void leaseRevoke() {
        LeasePort port = LeasePort.inMemory();
        long lease = port.grant(100);
        port.put("a", "1", lease);
        port.put("b", "2", lease);
        assertEquals(List.of("a", "b"), port.revoke(lease), "撤销连带挂靠 key 全清");
        assertNull(port.get("a"));
        assertNull(port.get("b"));
        assertEquals(List.of(), port.revoke(lease), "重复撤销幂等");
        assertThrows(IllegalArgumentException.class, () -> port.revoke(999), "未知租约撤销拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.keepAlive(lease), "撤销后保活拒绝");
    }

    @Test
    void watchEvents() {
        LeasePort port = LeasePort.inMemory();
        port.put("x1", "a", 0);
        port.put("x2", "b", 0);
        port.delete("x1");
        long all = port.watch(0);
        List<WatchHub.Event> events = port.collect(all);
        assertEquals(3, events.size());
        assertEquals(1, events.get(0).revision());
        assertEquals("PUT", events.get(0).type());
        assertEquals("a", events.get(0).value());
        assertEquals(2, events.get(1).revision());
        assertEquals(3, events.get(2).revision());
        assertEquals("DELETE", events.get(2).type());
        assertNull(events.get(2).value(), "DELETE 事件无值");
        assertEquals(List.of(2L, 3L), port.collect(port.watch(1)).stream().map(WatchHub.Event::revision).toList(),
                "起点不含，从起点修订之后收取");
        assertThrows(IllegalArgumentException.class, () -> port.watch(port.revision() + 1), "起点超前拒绝");
    }

    @Test
    void watchFilter() {
        LeasePort port = LeasePort.inMemory();
        port.put("a/1", "v", 0);
        port.put("b/1", "v", 0);
        port.put("a/2", "v", 0);
        port.put("c/1", "v", 0);
        assertEquals(4, port.collect(port.watch(0)).size(), "空前缀全收");
        List<WatchHub.Event> filtered = port.collect(port.watch(0, "a/"));
        assertEquals(List.of(1L, 3L), filtered.stream().map(WatchHub.Event::revision).toList(),
                "前缀过滤保序");
        assertEquals(List.of(2L), port.collect(port.watch(1, "b/")).stream().map(WatchHub.Event::revision).toList(),
                "起点+前缀叠加");
        assertEquals(List.of(), port.collect(port.watch(0, "z/")));
    }

    @Test
    void compaction() {
        LeasePort port = LeasePort.inMemory();
        port.put("k", "1", 0);
        port.put("k", "2", 0);
        port.put("k", "3", 0);
        port.put("k", "4", 0);
        long early = port.watch(0);
        assertEquals(4, port.collect(early).size());
        port.compact(2);
        assertEquals("4", port.get("k"), "压缩不删当前值");
        assertThrows(IllegalArgumentException.class, () -> port.getAt("k", 1), "早于压缩点历史读拒绝");
        assertEquals(List.of(3L, 4L), port.collect(early).stream().map(WatchHub.Event::revision).toList(),
                "压缩点之前事件不再发布");
        assertThrows(IllegalArgumentException.class, () -> port.watch(1), "起点早于压缩点拒绝");
        port.watch(2, "k");
        port.compact(2);
        assertThrows(IllegalArgumentException.class, () -> port.compact(1), "压缩点倒退拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.compact(port.revision() + 1), "压缩点越界拒绝");
    }

    @Test
    void leasePipeline() {
        LeasePort port = LeasePort.inMemory();
        long lease = port.grant(50);
        assertEquals(1, port.put("a/1", "v1", lease));
        assertEquals(2, port.put("b/1", "v2", lease));
        long watch = port.watch(0, "a/");
        port.advance(10);
        assertEquals(60, port.keepAlive(lease));
        assertEquals("v1", port.getAt("a/1", 1));
        port.advance(70);
        assertEquals(List.of("a/1", "b/1"), port.reclaim(), "到期回收连带挂靠 key");
        assertNull(port.get("a/1"));
        assertTrue(port.reclaimed(lease));
        assertTrue(port.collect(watch).stream().anyMatch(e -> e.type().equals("DELETE")), "回收产生删除事件");
        port.compact(port.revision());
        assertTrue(port.collect(watch).isEmpty(), "压缩后早期事件不再发布");
        assertEquals(List.of("term", "index"), port.raftShape(), "raftkernel 日志条目形状只读联动");
    }
}
