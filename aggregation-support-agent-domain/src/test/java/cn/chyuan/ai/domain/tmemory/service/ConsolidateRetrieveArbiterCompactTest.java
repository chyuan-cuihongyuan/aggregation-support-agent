package cn.chyuan.ai.domain.tmemory.service;

import cn.chyuan.ai.domain.tmemory.adapter.port.ISummaryPort;
import cn.chyuan.ai.domain.tmemory.model.valobj.MemoryEdgeVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AS5-AS8 单测（工单 0366/0367/0368/0369）：情节晋升/混合检索/冲突仲裁/压缩快照。
 */
class ConsolidateRetrieveArbiterCompactTest {

    @Test
    void 时间窗分组阈值晋升与模板兜底幂等() {
        BiTemporalEdgeFactory factory = new BiTemporalEdgeFactory();
        MemoryEdgeVO w1 = factory.create("华为", "总部位于", "深圳", 1000L, null, 0.7, "official", "EPISODIC");
        MemoryEdgeVO w2 = factory.create("华为", "总部位于", "深圳", 2000L, null, 0.8, "media", "EPISODIC");
        MemoryEdgeVO w2b = factory.create("张三", "入职", "华为", 2100L, null, 0.6, "ugc", "EPISODIC");
        // 端口失败 → 模板兜底；阈值=2
        EpisodeConsolidator consolidator = new EpisodeConsolidator(1000L, 2, facts -> {
            throw new IllegalStateException("挂");
        });
        EpisodeConsolidator.Consolidation result = consolidator.consolidate(List.of(w1, w2, w2b), factory, 0L);
        // 两个窗各一条摘要
        assertEquals(2, result.summaries().size());
        assertTrue(result.summaries().get(0).getObject().startsWith("情节摘要: "));
        // 华为-总部位于-深圳 跨两窗 → 晋升；张三-入职-华为 单窗不晋升
        assertEquals(1, result.promoted().size());
        MemoryEdgeVO promoted = result.promoted().get(0);
        assertEquals("SEMANTIC", promoted.getKind());
        assertEquals(List.of("1", "2"), promoted.getPromotedFrom());
        // 重放幂等：同输入结果一致
        EpisodeConsolidator.Consolidation replay = consolidator.consolidate(List.of(w1, w2, w2b), factory, 0L);
        assertEquals(result.promoted().size(), replay.promoted().size());
        assertEquals(result.summaries().size(), replay.summaries().size());
        // 端口正常采纳
        EpisodeConsolidator withPort = new EpisodeConsolidator(1000L, 2, facts -> "摘要[" + facts.size() + "]");
        assertEquals("摘要[1]", withPort.consolidate(List.of(w1), factory, 0L).summaries().get(0).getObject());
        // 非法配置
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new EpisodeConsolidator(0, 2, null));
    }

    @Test
    void 邻域扩展RRF融合与时间衰减TopK() {
        BiTemporalEdgeFactory factory = new BiTemporalEdgeFactory();
        MemoryEdgeVO seedEdge = factory.create("华为", "总部位于", "深圳", 5000L, null, 0.9, "official", "SEMANTIC");
        MemoryEdgeVO nearEdge = factory.create("深圳", "属于", "广东", 5000L, null, 0.8, "official", "SEMANTIC");
        MemoryEdgeVO farEdge = factory.create("火星", "有", "峡谷", 5000L, null, 0.9, "official", "SEMANTIC");
        HybridRetriever retriever = new HybridRetriever(1, 2, 1_000_000L);
        List<HybridRetriever.Retrieved> result = retriever.retrieve(
                List.of(seedEdge, nearEdge, farEdge),
                List.of("华为", "总部"),
                Set.of("华为"), 6000L);
        // 跳数 1 内只含 seedEdge/nearEdge；火星边被裁剪
        assertEquals(2, result.size());
        // 种子边：图+词双通道 → 融合分最高
        assertEquals("华为", result.get(0).edge().getSubject());
        assertEquals(2, result.get(0).channels().size());
        // 时间衰减：分值为正且单调
        assertTrue(result.get(0).score() >= result.get(1).score());
        // 失效边不参与
        MemoryEdgeVO dead = factory.invalidate(
                factory.create("华为", "前总部", "深圳", 100L, null, 0.9, "official", "SEMANTIC"), 200L, "SUPERSEDED");
        assertTrue(retriever.retrieve(List.of(seedEdge, dead), List.of("华为"), Set.of("华为"), 6000L)
                .stream().allMatch(r -> !r.edge().getEdgeId().equals(dead.getEdgeId())));
    }

    @Test
    void 加权仲裁与策略插拔() {
        BiTemporalEdgeFactory factory = new BiTemporalEdgeFactory();
        MemoryEdgeVO lowConf = factory.create("A", "位于", "B", 1000L, null, 0.4, "ugc", "SEMANTIC");
        MemoryEdgeVO highConf = factory.create("A", "位于", "C", 2000L, null, 0.9, "official", "SEMANTIC");
        ConflictArbiter arbiter = new ConflictArbiter();
        ConflictArbiter.Arbitration result = arbiter.arbitrate(List.of(lowConf, highConf), 3000L);
        assertEquals("C", result.winner().getObject());
        assertEquals(1, result.losers().size());
        assertTrue(result.losers().get(0).reason().contains("score="));
        // 自定义策略插拔生效
        ConflictArbiter custom = new ConflictArbiter(
                (candidates, now) -> new ConflictArbiter.Arbitration(candidates.get(0), List.of()));
        assertEquals("B", custom.arbitrate(List.of(lowConf, highConf), 3000L).winner().getObject());
        // 空候选
        org.junit.jupiter.api.Assertions.assertNull(arbiter.arbitrate(List.of(), 3000L).winner());
    }

    @Test
    void 失效归档低价值裁剪与快照重放等价() {
        BiTemporalEdgeFactory factory = new BiTemporalEdgeFactory();
        MemoryEdgeVO semantic = factory.create("华为", "总部位于", "深圳", 1L, null, 0.9, "official", "SEMANTIC");
        MemoryEdgeVO lowValue = factory.create("路人", "提过", "天气", 2L, null, 0.3, "ugc", "EPISODIC");
        lowValue = lowValue.toBuilder().score(0.1).accessCount(0).build();
        MemoryEdgeVO valuable = factory.create("张三", "入职", "华为", 3L, null, 0.8, "media", "EPISODIC");
        valuable = valuable.toBuilder().score(0.9).accessCount(5).build();
        MemoryEdgeVO invalid = factory.invalidate(
                factory.create("旧闻", "称", "X", 1L, null, 0.5, "ugc", "EPISODIC"), 10L, "CONFLICT");
        MemoryCompactor compactor = new MemoryCompactor(1.0);
        MemoryCompactor.Report report = compactor.compact(List.of(semantic, lowValue, valuable, invalid));
        // 失效归档；低价值（0.1×0<1.0 且非语义）裁剪；语义边保护；高价值保留
        assertEquals(1, report.archivedCount());
        assertEquals(1, report.prunedCount());
        assertEquals(2, report.activeCount());
        assertTrue(report.pruned().stream().noneMatch(e -> "SEMANTIC".equals(e.getKind())));
        // 快照导出确定性
        String snap1 = compactor.snapshot(report, "snap", 100L);
        String snap2 = compactor.snapshot(report, "snap", 100L);
        assertEquals(snap1, snap2);
        // 重放重建等价
        MiniSnapshotCodec.Snapshot restored = compactor.restore(snap1);
        assertEquals(2, restored.activeEdges().size());
        assertEquals(1, restored.archivedEdges().size());
        assertEquals("华为", restored.activeEdges().get(0).subject());
        assertEquals("张三", restored.activeEdges().get(1).subject());
        assertEquals("CONFLICT", restored.archivedEdges().get(0).invalidReason());
    }
}
