package cn.chyuan.ai.domain.storekernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LSM 存储内核 BE1-BE7 单测（工单 0472-0478）：
 * memtable+WAL/SST 段/leveled compaction/bloom/读写快照/区间迭代/段表登记。
 */
class StoreKernelTest {

    @Test
    void BE1_memtable有序与墓碑() {
        MemTable memtable = new MemTable(8);
        memtable.put("b", 2L, "vb");
        memtable.put("a", 1L, "va");
        memtable.put("a", 3L, null);
        assertEquals("vb", memtable.get("b").value());
        assertTrue(memtable.get("a").tombstone(), "同键新序列号墓碑覆盖");
        List<String> keys = memtable.tailMap("a").keySet().stream().toList();
        assertEquals(List.of("a", "b"), keys, "键有序");
    }

    @Test
    void BE1_wal追加滚动与恢复重放() {
        WriteAheadLog wal = new WriteAheadLog(3);
        for (long seq = 1; seq <= 7; seq++) {
            wal.append(new WriteAheadLog.Entry(seq, false, "k" + seq, "v" + seq));
        }
        assertEquals(3, wal.segmentCount(), "每 3 条滚动一段");
        List<WriteAheadLog.Entry> replayed = wal.replayFrom(4L);
        assertEquals(3, replayed.size(), "从序列号 4 之后重放");
        assertEquals("k5", replayed.get(0).key());
        assertThrows(IllegalArgumentException.class,
                () -> wal.append(new WriteAheadLog.Entry(1L, false, "x", "y")), "序列号必须单调");
    }

    @Test
    void BE2_sst段元数据与定位() {
        MemTable memtable = new MemTable(16);
        memtable.put("b", 2L, "vb");
        memtable.put("a", 1L, "va");
        memtable.put("c", 3L, null);
        SstSegment segment = SstSegment.build(1L, 0, new java.util.ArrayList<>(memtable.drain()));
        assertEquals("a", segment.minKey());
        assertEquals("c", segment.maxKey());
        assertEquals(0, segment.level());
        assertEquals(3, segment.rowCount());
        assertTrue(segment.byteSize() > 0);
        assertNotNull(segment.find("b"));
        assertNull(segment.find("d"));
        assertTrue(segment.mayContainKey("b"));
        assertFalse(segment.mayContainKey("e"));
        assertEquals("va", segment.tailFrom("a").get(0).value());
    }

    @Test
    void BE3_归并去重新版本胜与墓碑清除() {
        SstSegment old = SstSegment.fromRows(1L, 0, List.of(
                new SstSegment.Row("k1", 1L, "v1"),
                new SstSegment.Row("k2", 2L, "v2")));
        SstSegment newer = SstSegment.fromRows(2L, 0, List.of(
                new SstSegment.Row("k2", 5L, "v2-new"),
                new SstSegment.Row("k3", 6L, null)));
        LeveledCompaction compaction = new LeveledCompaction();
        LeveledCompaction.Result result = compaction.compact(
                new LeveledCompaction.Inputs(1, List.of(old, newer)), false, 3);
        assertEquals(3, result.output().rowCount(), "非底层墓碑保留");
        assertEquals("v2-new", result.output().find("k2").value(), "序列号大者胜");
        assertTrue(result.bytesRead() >= result.bytesWritten() || result.bytesWritten() > 0);
        LeveledCompaction.Result bottom = compaction.compact(
                new LeveledCompaction.Inputs(3, List.of(result.output())), true, 3);
        assertEquals(2, bottom.output().rowCount(), "底层墓碑清除");
        assertEquals(1, bottom.tombstonesCleared());
        assertTrue(compaction.writeAmplification() > 0, "写放大计数");
        assertThrows(IllegalStateException.class,
                () -> compaction.selectLevel0(List.of(), List.of(), 2), "层满才触发");
    }

    @Test
    void BE4_bloom假阴性不可能() {
        BloomFilter bloom = BloomFilter.create(1000, 0.01);
        for (int i = 0; i < 1000; i++) {
            bloom.add("key-" + i);
        }
        for (int i = 0; i < 1000; i++) {
            assertTrue(bloom.mightContain("key-" + i), "已插入键必须命中（无假阴性）");
        }
        int falsePositives = 0;
        for (int i = 0; i < 5000; i++) {
            if (bloom.mightContain("absent-" + i)) {
                falsePositives++;
            }
        }
        assertTrue(falsePositives < 5000 * 0.05, "假阳性应远低于 5%（参数 p=1%）: " + falsePositives);
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.create(0, 0.01));
    }

    @Test
    void BE5_读写路径与快照一致读() {
        LsmEngine engine = new LsmEngine(3, 2);
        engine.put("cfg/a", "1");
        long snapshot = engine.currentSequence();
        engine.put("cfg/a", "2");
        assertEquals("2", engine.get("cfg/a"));
        assertEquals("1", engine.getAt("cfg/a", snapshot), "快照读旧版本");
        engine.delete("cfg/a");
        assertNull(engine.get("cfg/a"), "墓碑命中即不存在");
        assertEquals("1", engine.getAt("cfg/a", snapshot), "快照读不受墓碑影响");
    }

    @Test
    void BE5_flush与compaction联动() {
        LsmEngine engine = new LsmEngine(3, 2);
        for (int i = 0; i < 8; i++) {
            engine.put("k" + i, "v" + i);
        }
        engine.put("k3", "v3-late");
        assertEquals("v3-late", engine.get("k3"), "跨段新版本胜");
        assertEquals("v0", engine.get("k0"), "跨段读取");
        LsmEngine.Stats stats = engine.stats();
        assertTrue(stats.l0Segments() <= 2 || stats.levelSegments()[1] > 0, "L0 达阈值触发 compaction");
        assertTrue(stats.bloomChecks() > 0, "bloom 参与查询");
    }

    @Test
    void BE6_区间迭代seek反转与前缀() {
        MemTable memtable = new MemTable(16);
        SstSegment segment = SstSegment.fromRows(1L, 0, List.of(
                new SstSegment.Row("cfg/a", 1L, "1"),
                new SstSegment.Row("cfg/b", 2L, "2"),
                new SstSegment.Row("db/x", 3L, "x"),
                new SstSegment.Row("gone", 4L, null)));
        RangeIterator iterator = RangeIterator.merge(List.of(),
                List.of(segment), 10L, null, true);
        iterator.seek("cfg/b");
        assertEquals("cfg/b", iterator.next().key());
        iterator.reverse();
        assertTrue(iterator.hasNext());
        assertEquals("db/x", iterator.next().key(), "反转后从尾部继续");
        RangeIterator prefixed = RangeIterator.merge(List.of(),
                List.of(segment), 10L, "cfg/", true);
        List<String> keys = new java.util.ArrayList<>();
        while (prefixed.hasNext()) {
            keys.add(prefixed.next().key());
        }
        assertEquals(List.of("cfg/a", "cfg/b"), keys, "前缀枚举+墓碑跳过");
    }

    @Test
    void BE7_段表登记幂等与淘汰() {
        SegmentRegistry registry = new SegmentRegistry();
        SstSegment segment = SstSegment.fromRows(11L, 1, List.of(new SstSegment.Row("a", 1L, "va")));
        registry.register(segment);
        registry.register(segment);
        assertEquals(1, registry.size(), "同段 id 幂等");
        assertTrue(registry.markCompacted(11L));
        assertFalse(registry.markCompacted(11L), "重复淘汰幂等");
        assertTrue(registry.activeSegments().isEmpty(), "淘汰后不活跃");
    }

    @Test
    void BE1_崩溃恢复重放幂等() {
        LsmEngine engine = new LsmEngine(3, 4);
        engine.put("k", "v");
        assertEquals("v", engine.get("k"));
        int applied = engine.recover();
        assertTrue(applied > 0, "WAL 重放条数");
        assertEquals("v", engine.get("k"), "重放后读取不变（幂等）");
    }
}
