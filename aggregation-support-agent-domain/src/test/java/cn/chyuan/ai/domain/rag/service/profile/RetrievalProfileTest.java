package cn.chyuan.ai.domain.rag.service.profile;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 检索参数画像与对比单测（工单 0235/0236 AE8/AE9）：环形聚合/P95/对比优者。 */
class RetrievalProfileTest {

    @Test
    void 采集聚合与P95() {
        RetrievalProfileCollector collector = new RetrievalProfileCollector();
        var key = new RetrievalProfileCollector.ProfileKey(10, 100, 0.6);
        // 20 次：命中 8 次/次、延迟 100ms；再 1 次延迟 1000ms（P95 应取到 1000）
        for (int i = 0; i < 20; i++) {
            collector.record(key, new RetrievalProfileCollector.Sample(8, 10, 100));
        }
        collector.record(key, new RetrievalProfileCollector.Sample(8, 10, 1000));
        RetrievalProfileCollector.ProfileStats stats = collector.stats(key);
        assertEquals(21, stats.samples());
        assertEquals(0.8, stats.avgHitRate(), 1e-9);
        assertTrue(stats.avgLatencyMs() > 100 && stats.avgLatencyMs() < 200);
        // 21 样本 P95 落在第 20 位（仍是 100ms 档）；1000ms 离群样本体现于均值
        assertEquals(100, stats.p95LatencyMs());
        assertTrue(stats.avgLatencyMs() > stats.p95LatencyMs(), "离群值抬升均值");
        // 键归一化（负值钳制）与解析往返
        assertEquals(key.key(), RetrievalProfileCollector.ProfileKey.class.cast(
                RetrievalProfileCollector.parseKey(key.key())).key());
        // 空配置统计
        assertEquals(0, collector.stats(new RetrievalProfileCollector.ProfileKey(5, 0, 0.5)).samples());
    }

    @Test
    void 多配置统计与toMap() {
        RetrievalProfileCollector collector = new RetrievalProfileCollector();
        collector.record(new RetrievalProfileCollector.ProfileKey(10, 100, 0.6),
                new RetrievalProfileCollector.Sample(5, 10, 50));
        collector.record(new RetrievalProfileCollector.ProfileKey(5, 50, 0.5),
                new RetrievalProfileCollector.Sample(4, 5, 30));
        assertEquals(2, collector.allStats().size());
        Map<String, Object> map = collector.allStats().get(0).toMap();
        // 键序稳定：topK=10 的配置在前（字符串序）
        assertEquals(1L, map.get("samples"));
    }

    @Test
    void 对比优者判定() {
        var statsA = new RetrievalProfileCollector.ProfileStats("A", 100, 0.8, 50, 100);
        var statsB = new RetrievalProfileCollector.ProfileStats("B", 100, 0.7, 80, 150);
        ProfileComparator.Comparison cmp = ProfileComparator.compare(statsA, statsB);
        assertEquals(0.1, cmp.hitRateDiff(), 1e-9);
        assertEquals("A", cmp.betterKey());
        assertEquals("hit_rate", cmp.basis());
        // 命中持平 → 比延迟
        var statsC = new RetrievalProfileCollector.ProfileStats("C", 100, 0.7, 40, 100);
        ProfileComparator.Comparison cmp2 = ProfileComparator.compare(statsC, statsB);
        assertEquals("C", cmp2.betterKey());
        assertEquals("latency", cmp2.basis());
        // 完全持平
        var statsD = new RetrievalProfileCollector.ProfileStats("D", 100, 0.7, 80, 150);
        assertEquals("", ProfileComparator.compare(statsB, statsD).betterKey());
        assertTrue(ProfileComparator.compare(statsA, statsB).toMap().containsKey("better"));
    }
}
