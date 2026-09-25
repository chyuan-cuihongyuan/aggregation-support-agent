package cn.chyuan.ai.domain.mvcckernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MVCC 内核测试（工单 0915-0922 DD1-DD8，etcd 思想）。
 * revision 递增/多版本读/watch 断点续传与断裂/compact/事务/租约到期/前缀范围/端口键值联动。
 */
class MvccKernelTest {

    @Test
    void revisionMonotonicAndModRevision() {
        MvccStore store = new MvccStore();
        long r1 = store.put("k", "v1");
        long r2 = store.put("k", "v2");
        assertEquals(1, r1);
        assertEquals(2, r2, "全局修订递增");
        assertEquals(2, store.modRevision("k"));
        assertTrue(store.delete("k"));
        assertEquals(3, store.modRevision("k"), "删除也占修订号");
        assertEquals(-1, store.modRevision("ghost"), "不存在键 -1");
    }

    @Test
    void multiVersionRead() {
        MvccStore store = new MvccStore();
        store.put("k", "v1");
        long rev2 = store.put("k", "v2");
        store.put("k", "v3");
        assertEquals("v1", store.getAt("k", rev2 - 1));
        assertEquals("v2", store.getAt("k", rev2));
        assertEquals("v3", store.getAt("k", 99));
        store.delete("k");
        assertNull(store.get("k"), "墓碑后最新读为空");
        assertEquals("v3", store.getAt("k", store.currentRevision() - 1), "历史版本仍可读");
    }

    @Test
    void watchStreamAndResume() {
        MvccStore store = new MvccStore();
        store.put("a", "1");
        store.put("b", "2");
        store.delete("a");
        List<MvccStore.Event> events = store.watch(2);
        assertEquals(2, events.size(), "从指定 revision 续传");
        assertEquals(MvccStore.EventType.PUT, events.get(0).type());
        assertEquals(MvccStore.EventType.DELETE, events.get(1).type());
        assertNull(events.get(1).value());
        assertEquals(3, store.watch(1).size(), "从头回放");
    }

    @Test
    void compactCleanupAndBreak() {
        MvccStore store = new MvccStore();
        store.put("k", "v1");
        long rev2 = store.put("k", "v2");
        store.put("k", "v3");
        store.delete("k");
        int removed = store.compact(rev2);
        assertTrue(removed >= 2, "压缩清除旧版本与事件");
        assertEquals(rev2, store.compactRevision());
        assertEquals("v2", store.getAt("k", rev2), "压缩边界版本保留");
        assertNull(store.get("k"), "键已删，最新读为空");
        assertThrows(IllegalStateException.class, () -> store.watch(1), "早于压缩点 watch 断裂拒绝");
        assertTrue(store.watch(store.currentRevision() + 1).isEmpty(), "压缩点之后新 watch 无事件");
    }

    @Test
    void txnCompareThenPut() {
        MvccStore store = new MvccStore();
        store.put("lock", "free");
        boolean applied = store.txn(
                java.util.Arrays.<String[]>asList(new String[]{"lock", "free"}),
                java.util.Arrays.<String[]>asList(new String[]{"lock", "held"}, new String[]{"owner", "agent-1"}));
        assertTrue(applied, "比较通过写入");
        assertEquals("held", store.get("lock"));
        assertEquals("agent-1", store.get("owner"));
        boolean failed = store.txn(
                java.util.Arrays.<String[]>asList(new String[]{"lock", "free"}),
                java.util.Arrays.<String[]>asList(new String[]{"owner", "agent-2"}));
        assertFalse(failed, "比较失败不写入");
        assertEquals("agent-1", store.get("owner"));
    }

    @Test
    void leaseExpiryBySteps() {
        MvccStore store = new MvccStore();
        long lease = store.grantLease(2);
        store.put("session", "s1", lease);
        assertEquals("s1", store.get("session"));
        store.advance();
        assertEquals("s1", store.get("session"), "未到期保留");
        List<String> removed = store.advance();
        assertEquals(List.of("session"), removed, "到期清除绑定键");
        assertNull(store.get("session"));
        assertThrows(IllegalArgumentException.class, () -> store.put("x", "v", 99L), "未知租约拒绝");
        assertThrows(IllegalArgumentException.class, () -> store.grantLease(0), "TTL 非法拒绝");
    }

    @Test
    void prefixRangeQueryAndDelete() {
        MvccStore store = new MvccStore();
        store.put("cfg/a", "1");
        store.put("cfg/b", "2");
        store.put("cfg/dead", "3");
        store.delete("cfg/dead");
        store.put("other", "4");
        Map<String, String> range = store.getByPrefix("cfg/");
        assertEquals(2, range.size(), "前缀查询不含墓碑");
        assertEquals("1", range.get("cfg/a"));
        int deleted = store.deleteByPrefix("cfg/");
        assertEquals(3, deleted, "墓碑键重复删除不计成功");
        assertNull(store.get("cfg/a"));
        assertEquals("4", store.get("other"), "前缀外不受影响");
    }

    @Test
    void portOrchestrationAndKeyValueLinkage() {
        MvccPort port = MvccPort.inMemory();
        port.put("k", "v1");
        assertEquals("v1", port.get("k"));
        long rev = port.put("k", "v2");
        assertEquals("v2", port.getAt("k", rev));
        var events = port.watch(rev);
        assertEquals(1, events.size());
        assertEquals(4, port.ingestKeyValue(Map.of("rk1", "a", "rk2", "b")), "rediskernel 键值形状只读联动");
        assertEquals("b", port.get("rk2"));
        assertEquals(3, port.compact(3));
    }
}
