package cn.chyuan.ai.domain.rediskernel.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis 内核域单测（工单 0647-0653 BY1-BY7，redis 思想）。
 * SDS 预分配与惰性释放/字典渐进 rehash 双表兼容/跳表定序排名/
 * 过期惰性+定期抽样/淘汰采样池与 LFU 衰减/发布订阅 glob/
 * 快照往返与损坏拒绝/统计登记。
 */
class RedisKernelTest {

    @Test
    void sdsAppendPreallocatesByRedisPolicy() {
        Sds s = Sds.wrap("ab".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, s.length());
        assertEquals(2, s.alloc());
        s.append("c".getBytes(StandardCharsets.UTF_8));
        assertEquals(3, s.length());
        assertEquals(6, s.alloc(), "新长 3<1MB 应翻倍预分配");
        assertEquals(3, s.freeAvailable());

        byte[] big = new byte[Sds.MAX_PREALLOC];
        s.append(big);
        assertEquals(3 + Sds.MAX_PREALLOC, s.length());
        assertEquals(3 + 2 * Sds.MAX_PREALLOC, s.alloc(), "≥1MB 应追加补 1MB");

        s.clear();
        assertEquals(0, s.length(), "惰性释放后长度归零");
        assertEquals(3 + 2 * Sds.MAX_PREALLOC, s.alloc(), "惰性释放保留缓冲待复用");
    }

    @Test
    void sdsBinarySafeAndRejectsIllegal() {
        byte[] binary = {0x61, 0x00, 0x62, (byte) 0xFF};
        Sds s = Sds.wrap(binary);
        assertEquals(4, s.length(), "二进制安全按长度取值");
        assertArrayEquals(binary, s.bytes());
        assertThrows(IllegalArgumentException.class, () -> Sds.wrap(null));
        assertThrows(IllegalArgumentException.class, () -> Sds.wrap(new byte[0]).append(null));
        assertThrows(IllegalArgumentException.class, () -> Sds.empty(-1));
    }

    @Test
    void dictProgressiveRehashServesBothTables() {
        Dict<String> dict = new Dict<>();
        for (int i = 0; i < 4; i++) {
            assertNull(dict.put("k" + i, "v" + i));
        }
        assertTrue(dict.isRehashing(), "负载因子达 1 应触发渐进扩容");
        assertEquals("v0", dict.get("k0"), "迁移中旧表键可读");
        assertTrue(dict.stepUntilDone(100), "步进上限内完成迁移");
        assertFalse(dict.isRehashing());
        assertEquals(4, dict.size());
        for (int i = 0; i < 4; i++) {
            assertEquals("v" + i, dict.get("k" + i), "迁移完成数据无损");
        }
        assertNull(dict.put("k4", "v4"));
        assertFalse(dict.isRehashing(), "used 5 < size 8 不再触发");

        assertEquals("v4", dict.remove("k4"));
        while (dict.size() > 1) {
            assertNotNull(dict.remove("k" + (dict.size() - 1)));
        }
        assertFalse(dict.isRehashing(), "used 1/8=0.125 > 0.1 不触发收缩（redis 同语义）");
        assertNotNull(dict.remove("k0"));
        assertTrue(dict.isRehashing(), "used 0 < 0.1*size 触发收缩");
        assertTrue(dict.stepUntilDone(100));
        assertEquals(0, dict.size());
        assertTrue(dict.keys().isEmpty(), "收缩完成后单表键数一致");
    }

    @Test
    void dictRejectsIllegal() {
        Dict<String> dict = new Dict<>();
        assertThrows(IllegalArgumentException.class, () -> dict.get(null));
        assertThrows(IllegalArgumentException.class, () -> dict.put(null, "v"));
        assertThrows(IllegalArgumentException.class, () -> dict.put("k", null));
    }

    @Test
    void zsetOrderRankRangeAndUpdate() {
        ZSet zset = new ZSet(new Random(7));
        zset.add(5, "a");
        zset.add(1, "b");
        zset.add(3, "c");
        zset.add(3, "d");
        assertEquals(4, zset.size());
        assertEquals(List.of("b", "c", "d", "a"), zset.members(), "score+member 双键定序");
        assertEquals(0, zset.rank("b"));
        assertEquals(3, zset.rank("a"));
        assertEquals(-1, zset.rank("ghost"));
        assertEquals(List.of("c", "d"), zset.rangeByScore(2, 3), "同分按成员字典序");
        assertEquals(List.of("b"), zset.rangeByScore(1, 1));

        zset.add(0.5, "a");
        assertEquals(List.of("a", "b", "c", "d"), zset.members(), "重复成员更新分数后重排");
        assertEquals(0, zset.rank("a"));
        assertEquals(0.5, zset.score("a"));
    }

    @Test
    void zsetRejectsIllegal() {
        ZSet zset = new ZSet(new Random(1));
        assertThrows(IllegalArgumentException.class, () -> zset.add(Double.NaN, "x"));
        assertThrows(IllegalArgumentException.class, () -> zset.add(1, ""));
        assertThrows(IllegalArgumentException.class, () -> zset.add(1, null));
        assertThrows(IllegalArgumentException.class, () -> zset.rank(null));
        assertThrows(IllegalArgumentException.class, () -> zset.rangeByScore(5, 1));
    }

    @Test
    void expireLazyAndActiveSampling() {
        Expires expires = new Expires();
        expires.set("k1", 100);
        expires.set("k2", 200);
        assertTrue(expires.isExpired("k1", 101));
        assertTrue(expires.lazyExpire("k1", 101), "惰性删除命中");
        assertFalse(expires.isExpired("k1", 102), "摘除后不再过期");
        assertFalse(expires.lazyExpire("k2", 101));
        assertFalse(expires.isExpired("k3", 101), "持久键不在 TTL 表");

        for (int i = 0; i < 10; i++) {
            expires.set("e" + i, 50);
        }
        List<String> round1 = expires.sampleExpired(100, 3);
        assertEquals(2, round1.size(), "抽样 3 个仅未过期 k2 占一位");
        List<String> round2 = expires.sampleExpired(100, 3);
        assertEquals(2, round2.size(), "k2 持续占用一个抽样位");
        List<String> round3 = expires.sampleExpired(100, 3);
        assertEquals(2, round3.size());
        List<String> round4 = expires.sampleExpired(100, 3);
        assertEquals(2, round4.size());
        List<String> round5 = expires.sampleExpired(100, 3);
        assertEquals(2, round5.size(), "末轮只扫剩余键");
        assertEquals(1, expires.size(), "仅未到期的 k2 保留");
        assertTrue(expires.lazyExpire("k2", 200), "到期后惰性删除命中");
        assertEquals(0, expires.size());
        assertThrows(IllegalArgumentException.class, () -> expires.set(null, 1));
        assertThrows(IllegalArgumentException.class, () -> expires.set("k", 0));
        assertThrows(IllegalArgumentException.class, () -> expires.sampleExpired(1, 0));
    }

    @Test
    void evictionLruPoolEvictsColdest() {
        Eviction eviction = new Eviction(new Random(42));
        eviction.setMaxMemory(1000);
        eviction.setPolicy(Eviction.Policy.ALLKEYS_LRU);
        for (int i = 1; i <= 5; i++) {
            eviction.register("k" + i, 100, 0, false);
        }
        assertEquals(500, eviction.usedBytes());
        assertTrue(eviction.admits(400));
        assertFalse(eviction.admits(600), "超限应拒绝");

        eviction.touch("k1", 100);
        eviction.touch("k2", 100);
        String victim = eviction.evictOne(100);
        assertNotNull(victim);
        assertTrue(victim.equals("k3") || victim.equals("k4") || victim.equals("k5"),
                "采样池应选中最久未访问者而非 k1/k2");
        assertEquals(400, eviction.usedBytes());
        assertEquals(1, eviction.evictionCount());
        assertEquals(victim, eviction.lastEvicted());
    }

    @Test
    void evictionNoevictionRejectsAndVolatileScope() {
        Eviction eviction = new Eviction(new Random(42));
        eviction.setMaxMemory(500);
        eviction.setPolicy(Eviction.Policy.NO_EVICTION);
        for (int i = 1; i <= 5; i++) {
            eviction.register("k" + i, 100, 0, false);
        }
        assertFalse(eviction.admits(1), "noeviction 超限拒绝");
        eviction.setPolicy(Eviction.Policy.VOLATILE_LRU);
        assertNull(eviction.evictOne(0), "无 TTL 键时 volatile 无候选可逐");
        eviction.setVolatile("k1", true);
        assertEquals("k1", eviction.evictOne(0), "volatile 只逐带 TTL 键");
        assertEquals(400, eviction.usedBytes());
    }

    @Test
    void evictionLfuCounterAndDecay() {
        Eviction eviction = new Eviction(new Random(1));
        eviction.setPolicy(Eviction.Policy.ALLKEYS_LFU);
        eviction.register("hot", 10, 0, false);
        Eviction.Meta meta = eviction.meta("hot");
        assertEquals(Eviction.LFU_INIT_VAL, meta.lfu);
        for (int i = 0; i < 200; i++) {
            eviction.touch("hot", 1000);
        }
        assertTrue(meta.lfu > Eviction.LFU_INIT_VAL, "访问应使 LFU 计数对数增长");
        assertTrue(meta.lfu <= 255);
        long before = meta.lfu;
        eviction.touch("hot", 1000 + 61 * 60_000L);
        assertTrue(meta.lfu <= before, "61 分钟衰减后计数不增");
        assertEquals(Eviction.LFU_INIT_VAL, Eviction.lfuLogIncr((byte) 4, new Random(1)),
                "低于初值直接加一");
        assertEquals((byte) 255, Eviction.lfuLogIncr((byte) 255, new Random(1)), "饱和封顶");
    }

    @Test
    void pubsubExactPatternGlobAndCount() {
        PubSub pubsub = new PubSub();
        pubsub.subscribe("news", "c1");
        pubsub.subscribe("news", "c2");
        pubsub.psubscribe("news*", "c3");
        assertEquals(3, pubsub.publish("news", "m1"), "精确 2 + pattern 1");
        assertEquals(1, pubsub.publish("news.sport", "m2"), "仅 pattern 触达");
        assertEquals(0, pubsub.publish("ghost", "x"), "无订阅者即丢弃");

        List<PubSub.Delivery> inbox = pubsub.inbox("c3");
        assertEquals(2, inbox.size());
        assertTrue(inbox.get(0).byPattern());
        assertTrue(pubsub.inbox("c1").get(0).message().equals("m1"));

        pubsub.unsubscribe("news", "c1");
        assertEquals(2, pubsub.publish("news", "m3"));
        pubsub.punsubscribe("news*", "c3");
        assertEquals(1, pubsub.publish("news", "m4"));
        assertEquals(0, pubsub.subscriberCount("news.sport"));

        assertTrue(PubSub.globMatch("h?llo", "hello"));
        assertTrue(PubSub.globMatch("h[ae]llo", "hallo"));
        assertTrue(PubSub.globMatch("h*llo", "heeeeello"));
        assertFalse(PubSub.globMatch("h[^e]llo", "hello"));
        assertFalse(PubSub.globMatch("news.*", "news"));
        assertThrows(IllegalArgumentException.class, () -> pubsub.publish(null, "m"));
        assertThrows(IllegalArgumentException.class, () -> pubsub.subscribe("c", ""));
    }

    @Test
    void snapshotDumpLoadRoundtripAndRejectsCorruption() {
        List<Snapshot.Entry> entries = List.of(
                new Snapshot.Entry("k1", new Snapshot.Str("v1".getBytes(StandardCharsets.UTF_8))),
                new Snapshot.Entry("zk", new Snapshot.Zset(List.of(
                        new Snapshot.MemberScore("a", 1.5d),
                        new Snapshot.MemberScore("b", -2d)))));
        byte[] dump = Snapshot.dump(entries);
        List<Snapshot.Entry> loaded = Snapshot.load(dump);
        assertEquals(2, loaded.size());
        assertEquals("k1", loaded.get(0).key());
        assertArrayEquals("v1".getBytes(StandardCharsets.UTF_8),
                ((Snapshot.Str) loaded.get(0).value()).data());
        Snapshot.Zset zset = (Snapshot.Zset) loaded.get(1).value();
        assertEquals(2, zset.items().size());
        assertEquals(1.5d, zset.items().get(0).score());
        assertEquals("b", zset.items().get(1).member());

        byte[] corrupt = dump.clone();
        corrupt[10] ^= 0x5A;
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Snapshot.load(corrupt));
        assertTrue(thrown.getMessage().contains("CRC32"), "字节翻转应被 CRC 拦截");
        assertThrows(IllegalArgumentException.class, () -> Snapshot.load(Arrays.copyOf(dump, dump.length - 3)),
                "截断快照应拒绝");
        assertThrows(IllegalArgumentException.class, () -> Snapshot.load(null));
    }

    @Test
    void snapshotStatsRegistered() {
        Snapshot.Stats stats = new Snapshot.Stats();
        stats.record("manual", 3, 120, 1, 1000);
        stats.record("dump", 5, 300, 2, 2000);
        assertEquals(2, stats.all().size());
        assertEquals("manual", stats.all().get(0).scene());
        assertEquals(300, stats.all().get(1).bytes());
        assertThrows(IllegalArgumentException.class, () -> stats.record("", 1, 1, 1, 1));
    }
}
