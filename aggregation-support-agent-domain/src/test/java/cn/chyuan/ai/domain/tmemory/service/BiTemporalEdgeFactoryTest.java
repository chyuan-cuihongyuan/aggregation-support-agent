package cn.chyuan.ai.domain.tmemory.service;

import cn.chyuan.ai.domain.tmemory.model.valobj.MemoryEdgeVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AS1 单测（工单 0362）：bi-temporal 边构建/合法性校验/事务时间单调/显式失效。
 */
class BiTemporalEdgeFactoryTest {

    @Test
    void 边构建与合法性校验() {
        BiTemporalEdgeFactory factory = new BiTemporalEdgeFactory();
        MemoryEdgeVO edge = factory.create("华为", "总部位于", "深圳",
                1000L, null, 0.9, "official", "SEMANTIC");
        assertEquals("me-1", edge.getEdgeId());
        assertEquals(1, edge.getIngestSeq());
        assertTrue(edge.active());
        assertNull(edge.getValidTo());

        // 空三元组拒绝
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(" ", "位于", "深圳", 1L, null, 0.5, "src", "EPISODIC"));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create("华为", " ", "深圳", 1L, null, 0.5, "src", "EPISODIC"));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create("华为", "位于", " ", 1L, null, 0.5, "src", "EPISODIC"));
        // 失效早于生效拒绝
        assertThrows(IllegalArgumentException.class,
                () -> factory.create("华为", "位于", "深圳", 100L, 50L, 0.5, "src", "EPISODIC"));
        // 置信度越界拒绝
        assertThrows(IllegalArgumentException.class,
                () -> factory.create("华为", "位于", "深圳", 1L, null, 1.5, "src", "EPISODIC"));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create("华为", "位于", "深圳", 1L, null, -0.1, "src", "EPISODIC"));
        // 非法类别拒绝
        assertThrows(IllegalArgumentException.class,
                () -> factory.create("华为", "位于", "深圳", 1L, null, 0.5, "src", "OTHER"));
    }

    @Test
    void 事务时间单调递增与同刻并入按序() {
        BiTemporalEdgeFactory factory = new BiTemporalEdgeFactory();
        MemoryEdgeVO first = factory.create("A", "位于", "B", 100L, null, 0.5, "src", "EPISODIC");
        MemoryEdgeVO second = factory.create("A", "位于", "C", 100L, null, 0.5, "src", "EPISODIC");
        assertEquals(1, first.getIngestSeq());
        assertEquals(2, second.getIngestSeq());
        assertTrue(second.getIngestSeq() > first.getIngestSeq(), "同事实时刻并入按序号单调");
        assertEquals(2, factory.allocated());
    }

    @Test
    void 显式失效与重复失效拒绝() {
        BiTemporalEdgeFactory factory = new BiTemporalEdgeFactory();
        MemoryEdgeVO edge = factory.create("A", "担任", "B", 100L, null, 0.8, "official", "SEMANTIC");
        MemoryEdgeVO invalidated = factory.invalidate(edge, 200L, "SUPERSEDED");
        assertFalse(invalidated.active());
        assertEquals("SUPERSEDED", invalidated.getInvalidReason());
        assertEquals(200L, invalidated.getValidTo());
        // 原边不受影响（副本语义）
        assertTrue(edge.active());
        // 重复失效拒绝
        assertThrows(IllegalArgumentException.class,
                () -> factory.invalidate(invalidated, 300L, "CONFLICT"));
        // 失效早于生效拒绝
        assertThrows(IllegalArgumentException.class,
                () -> factory.invalidate(edge, 50L, "SUPERSEDED"));
        assertNotNull(invalidated.getEdgeId());
    }
}
