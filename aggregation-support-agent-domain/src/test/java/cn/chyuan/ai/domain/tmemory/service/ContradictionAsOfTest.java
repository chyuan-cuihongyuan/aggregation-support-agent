package cn.chyuan.ai.domain.tmemory.service;

import cn.chyuan.ai.domain.tmemory.model.valobj.EdgeVisibilityVO;
import cn.chyuan.ai.domain.tmemory.model.valobj.MemoryEdgeVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AS3/AS4 单测（工单 0364/0365）：冲突裁决/全等去重/失效后不可见/as-of 边界/可解释。
 */
class ContradictionAsOfTest {

    private MemoryEdgeVO edge(BiTemporalEdgeFactory factory, String s, String p, String o,
                              long from, double conf) {
        return factory.create(s, p, o, from, null, conf, "official", "SEMANTIC");
    }

    @Test
    void 冲突裁决胜者留失效者打戳() {
        BiTemporalEdgeFactory factory = new BiTemporalEdgeFactory();
        MemoryEdgeVO old = edge(factory, "华为", "总部位于", "深圳", 100L, 0.6);
        MemoryEdgeVO better = edge(factory, "华为", "总部位于", "东莞", 200L, 0.9);
        ContradictionDetector detector = new ContradictionDetector();
        ContradictionDetector.Report report = detector.detect(List.of(old, better), factory);
        // 高置信度胜出，败者打失效戳=胜者生效时间
        assertEquals(1, report.resolved().size());
        assertEquals("东莞", report.resolved().get(0).getObject());
        assertEquals(1, report.invalidated().size());
        MemoryEdgeVO invalidated = report.invalidated().get(0);
        assertEquals("深圳", invalidated.getObject());
        assertEquals(200L, invalidated.getValidTo());
        assertEquals("CONFLICT", invalidated.getInvalidReason());
        assertTrue(report.duplicates().isEmpty());
    }

    @Test
    void 全等事实去重不失效() {
        BiTemporalEdgeFactory factory = new BiTemporalEdgeFactory();
        MemoryEdgeVO first = edge(factory, "华为", "总部位于", "深圳", 100L, 0.7);
        MemoryEdgeVO restated = edge(factory, "华为 ", "总部位于", "深圳", 150L, 0.9);
        ContradictionDetector.Report report = new ContradictionDetector().detect(List.of(first, restated), factory);
        // 全等（归一后同宾语）保留最高置信度，落重复不失效
        assertEquals(1, report.resolved().size());
        assertEquals(0, report.invalidated().size());
        assertEquals(1, report.duplicates().size());
        assertEquals(0.9, report.resolved().get(0).getConfidence());
    }

    @Test
    void 不同谓语不冲突() {
        BiTemporalEdgeFactory factory = new BiTemporalEdgeFactory();
        MemoryEdgeVO a = edge(factory, "华为", "总部位于", "深圳", 100L, 0.6);
        MemoryEdgeVO b = edge(factory, "华为", "研发中心位于", "东莞", 100L, 0.6);
        ContradictionDetector.Report report = new ContradictionDetector().detect(List.of(a, b), factory);
        assertEquals(2, report.resolved().size());
        assertTrue(report.invalidated().isEmpty());
    }

    @Test
    void 失效后asOf不可见且边界开区间() {
        BiTemporalEdgeFactory factory = new BiTemporalEdgeFactory();
        AsOfQueryEngine engine = new AsOfQueryEngine();
        MemoryEdgeVO active = edge(factory, "A", "位于", "B", 100L, 0.8);
        MemoryEdgeVO invalidated = factory.invalidate(edge(factory, "C", "位于", "D", 100L, 0.8), 300L, "SUPERSEDED");
        MemoryEdgeVO future = edge(factory, "E", "位于", "F", 500L, 0.8);
        List<MemoryEdgeVO> all = List.of(active, invalidated, future);

        // t=300：invalidated 边失效边界开区间（validTo=300 不可见）；future 未生效
        List<MemoryEdgeVO> view = engine.asOfValid(all, 300L);
        assertEquals(List.of(active), view);
        // t=299：invalidated 仍可见
        assertEquals(2, engine.asOfValid(all, 299L).size());

        // 事务口径：序号 3 已含全部（含失效）
        assertEquals(3, engine.asOfIngested(all, 3).size());
        assertEquals(1, engine.asOfIngested(all, 1).size());

        // 可解释：逐边原因
        List<EdgeVisibilityVO> explained = engine.explainValid(all, 300L);
        assertEquals(EdgeVisibilityVO.REASON_ACTIVE, explained.get(0).getReason());
        assertEquals(EdgeVisibilityVO.REASON_INVALIDATED, explained.get(1).getReason());
        assertEquals(EdgeVisibilityVO.REASON_NOT_YET, explained.get(2).getReason());
        // 差异集：已入库且在 t 前失效的边
        assertEquals(List.of(invalidated), engine.invalidatedBetween(all, 3, 300L));
    }
}
