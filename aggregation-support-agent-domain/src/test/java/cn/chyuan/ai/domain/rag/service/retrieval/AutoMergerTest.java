package cn.chyuan.ai.domain.rag.service.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自动合并检索单测（工单 0230 AE3）：上卷/保留/去重最优分。 */
class AutoMergerTest {

    private static AutoMerger.ChildHit hit(String chunkId, String parent, double score) {
        return new AutoMerger.ChildHit(chunkId, parent, score, "文本-" + chunkId);
    }

    @Test
    void 命中率超阈上卷父块() {
        AutoMerger merger = new AutoMerger(0.6);
        List<AutoMerger.MergedHit> out = merger.merge(
                List.of(hit("c1", "p1", 0.9), hit("c2", "p1", 0.7), hit("c3", "p2", 0.5)), 3);
        // p1 命中 2/3=0.67 ≥ 0.6 → 上卷；p2 命中 1/3 → 保留子块
        assertEquals(2, out.size());
        AutoMerger.MergedHit first = out.get(0);
        assertTrue(first.mergedParent());
        assertEquals("p1", first.id());
        assertEquals(0.9, first.score(), "上卷取组内最高分");
        assertFalse(out.get(1).mergedParent());
    }

    @Test
    void 无父块孤儿保留() {
        AutoMerger merger = new AutoMerger(0.6);
        List<AutoMerger.MergedHit> out = merger.merge(
                List.of(new AutoMerger.ChildHit("c9", null, 0.8, "孤儿")), 3);
        assertEquals(1, out.size());
        assertFalse(out.get(0).mergedParent());
        assertEquals("c9", out.get(0).id());
    }

    @Test
    void 空输入与阈值语义() {
        assertTrue(new AutoMerger(0.6).merge(List.of(), 3).isEmpty());
        // 阈值边界：2/3=0.667 ≥ 0.6 上卷；1/2=0.5 < 0.6 保留
        assertTrue(new AutoMerger(0.6).merge(
                List.of(hit("a", "p", 0.9), hit("b", "p", 0.8)), 3).get(0).mergedParent());
        // 1/2=0.5 < 0.6 → 保留子块（1 条）
        assertEquals(1, new AutoMerger(0.6).merge(
                List.of(hit("a", "p", 0.9)), 2).size());
    }
}
