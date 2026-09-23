package cn.chyuan.ai.domain.rediskernel.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedisPort 组合管线测试（工单 0654 BY8，redis 思想）。
 * 命令统一编排（SET/GET/DEL/EXPIRE/ZADD/ZRANGEBYSCORE/PUBLISH）/
 * RDB 快照导出载入与统计/段行只读联动形态/淘汰与内存记账。
 */
class RedisPortPipelineTest {

    @Test
    void portCommandChainWithExpireAndTypes() {
        RedisPort port = new RedisPort.InMemoryRedis(new Random(7));
        assertEquals(1L, port.set("a", "1".getBytes(StandardCharsets.UTF_8)).num());
        port.set("b", "2".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("1".getBytes(StandardCharsets.UTF_8), port.get("a").str());

        port.zadd("z", 1.0, "m1");
        port.zadd("z", 2.0, "m2");
        assertEquals(List.of("m1", "m2"), port.zrangeByScore("z", 1, 2).list());
        assertTrue(port.get("z").error().contains("WRONGTYPE"), "串读 zset 应拒绝");

        assertEquals(1L, port.expire("a", 50).num());
        port.advanceTime(60);
        assertNull(port.get("a").str(), "过期键惰性删除后不可见");
        assertEquals(2, port.keyCount());
        assertEquals(1L, port.del("b").num());
        assertEquals(1, port.keyCount());

        port.subscribe("ch", "c1");
        port.psubscribe("ch*", "c2");
        assertEquals(2L, port.publish("ch", "hello").num());
        assertEquals(1, port.inbox("c2").size());
    }

    @Test
    void portSnapshotDumpLoadAndSegmentRows() {
        RedisPort port = new RedisPort.InMemoryRedis(new Random(3));
        port.set("a", "1".getBytes(StandardCharsets.UTF_8));
        port.zadd("z", 1.0, "m1");
        port.zadd("z", 2.0, "m2");

        byte[] dump = port.dump();
        assertEquals(1, port.snapshotStats().all().size());
        assertEquals("manual", port.snapshotStats().all().get(0).scene());
        assertEquals(2, port.load(dump), "载入返回键数");
        assertArrayEquals("1".getBytes(StandardCharsets.UTF_8), port.get("a").str());
        assertEquals(List.of("m1", "m2"), port.zrangeByScore("z", 0, 10).list());
        assertEquals(2, port.keyCount());

        List<String> rows = port.snapshotAsSegmentRows();
        assertEquals(List.of("a\tSTR\t1", "z\tZSET\t2"), rows.stream().sorted().toList(),
                "段行形态（键/类型/规模）供 storekernel 只读联动");
    }

    @Test
    void portEvictionPolicyAndMemoryAccounting() {
        RedisPort port = new RedisPort.InMemoryRedis(new Random(11));
        port.configure(Eviction.Policy.ALLKEYS_LRU, 300);
        byte[] payload = new byte[100];
        assertEquals(1L, port.set("k1", payload).num());
        assertEquals(1L, port.set("k2", payload).num(), "写入触发 LRU 淘汰最冷 k1");
        assertNull(port.get("k1").str(), "k1 应被采样池淘汰");
        assertArrayEquals(payload, port.get("k2").str());
        assertTrue(port.memoryUsage() <= 300, "记账不超上限");
        assertEquals(1, port.keyCount());

        port.configure(Eviction.Policy.NO_EVICTION, 10);
        RedisPort.Reply err = port.set("k3", payload);
        assertEquals(RedisPort.Status.ERR, err.status());
        assertTrue(err.error().contains("OOM"), "noeviction 超限拒绝写");
        assertNotNull(port.get("k2").str(), "拒绝写不破坏既有键");
    }
}
