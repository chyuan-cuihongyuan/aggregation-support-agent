package cn.chyuan.ai.domain.memory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 记忆时间衰减单测（工单 0232 AE5）：单调性/半衰期/排序。 */
class MemoryDecayScoreTest {

    @Test
    void 分数单调性与半衰期() {
        MemoryDecayScore scorer = new MemoryDecayScore(7);
        long now = 1_000_000_000L;
        double fresh = scorer.score(1, now, now);
        double weekOld = scorer.score(1, now - 7L * 86_400_000L, now);
        double monthOld = scorer.score(1, now - 30L * 86_400_000L, now);
        // 频次权重：基准 1 频 = 1.2 权重；半衰期恰为权重一半
        assertEquals(1.2d, fresh, 1e-9);
        assertEquals(0.6d, weekOld, 1e-9);
        assertTrue(monthOld < weekOld, "越久越低");
        assertTrue(scorer.score(3, now, now) > fresh, "频次越高越高");
        // 半衰期因子
        assertEquals(0.5d, MemoryDecayScore.decayFactor(7, 7), 1e-9);
        assertEquals(1.0d, MemoryDecayScore.decayFactor(7, 0), 1e-9);
        // 未来时间不加分
        assertEquals(1.2d, scorer.score(1, now + 999_999L, now), 1e-9);
    }

    @Test
    void 按衰减分排序() {
        MemoryDecayScore scorer = new MemoryDecayScore(7);
        long now = 0;
        // m0 高频但很久前；m1 低频但刚访问 → m1 排前
        List<Integer> ranked = scorer.rankByDecay(
                List.of(5, 1), List.of(now - 30L * 86_400_000L, now), now);
        assertEquals(1, ranked.get(0));
        assertEquals(0, ranked.get(1));
        // 空输入
        assertTrue(scorer.rankByDecay(null, null, now).isEmpty());
    }
}
