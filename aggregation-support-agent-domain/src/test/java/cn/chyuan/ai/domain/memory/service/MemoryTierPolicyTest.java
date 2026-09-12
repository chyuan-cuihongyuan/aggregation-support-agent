package cn.chyuan.ai.domain.memory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 记忆晋升降级单测（工单 0231 AE4）：频次晋升/超期降级/边界。 */
class MemoryTierPolicyTest {

    @Test
    void 频次达标晋升() {
        MemoryTierPolicy policy = new MemoryTierPolicy(3, 1000);
        List<MemoryTierPolicy.TierTransition> out = policy.decide(List.of(
                new MemoryTierPolicy.MemoryView("m1", MemoryTierPolicy.TIER_ARCHIVE, 3, 0),
                new MemoryTierPolicy.MemoryView("m2", MemoryTierPolicy.TIER_ARCHIVE, 2, 0)), 500);
        assertEquals(1, out.size());
        assertEquals("m1", out.get(0).memoryId());
        assertEquals(MemoryTierPolicy.TIER_CORE, out.get(0).toTier());
    }

    @Test
    void 超期未访问降级() {
        MemoryTierPolicy policy = new MemoryTierPolicy(3, 1000);
        List<MemoryTierPolicy.TierTransition> out = policy.decide(List.of(
                new MemoryTierPolicy.MemoryView("m1", MemoryTierPolicy.TIER_CORE, 2, 0)), 2000);
        assertEquals(1, out.size());
        assertEquals(MemoryTierPolicy.TIER_ARCHIVE, out.get(0).toTier());
        // 未超期保持
        assertTrue(policy.decide(List.of(
                new MemoryTierPolicy.MemoryView("m2", MemoryTierPolicy.TIER_CORE, 2, 1500)), 2000).isEmpty());
        // 已达标核心不降级
        assertTrue(policy.decide(List.of(
                new MemoryTierPolicy.MemoryView("m3", MemoryTierPolicy.TIER_CORE, 5, 0)), 999_999).isEmpty());
    }

    @Test
    void 默认策略与空输入() {
        MemoryTierPolicy policy = new MemoryTierPolicy();
        assertEquals(3, policy.promoteThreshold());
        assertTrue(policy.decide(null, 0).isEmpty());
        assertTrue(policy.decide(List.of(), 0).isEmpty());
    }
}
