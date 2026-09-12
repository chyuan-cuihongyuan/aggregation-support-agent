package cn.chyuan.ai.domain.memory.service;

import java.util.List;

/**
 * 记忆晋升降级策略纯函数（工单 0231 AE4，借鉴 Letta/MemGPT memory hierarchy）—
 * CORE（核心，常驻上下文）/ ARCHIVE（归档）双层；访问频次 ≥ promoteThreshold 晋升，
 * 距上次访问超过 idleMs 且未达晋升线降级。Clock（nowMs）注入可测。
 *
 * @author chyuan
 */
public final class MemoryTierPolicy {

    public static final String TIER_CORE = "CORE";
    public static final String TIER_ARCHIVE = "ARCHIVE";

    /** 记忆条目视图（memory 域实体的只读投影） */
    public record MemoryView(String memoryId, String tier, int accessCount, long lastAccessAt) {
    }

    /** 迁移动作 */
    public record TierTransition(String memoryId, String fromTier, String toTier, String reason) {
    }

    private final int promoteThreshold;
    private final long idleMs;

    public MemoryTierPolicy(int promoteThreshold, long idleMs) {
        this.promoteThreshold = Math.max(1, promoteThreshold);
        this.idleMs = Math.max(0, idleMs);
    }

    /** 默认：频次 3 晋升，7 天未访问降级 */
    public MemoryTierPolicy() {
        this(3, 7L * 24 * 3600 * 1000);
    }

    /** 批量决策：返回需要迁移动作的条目（已是目标层的不重复迁移） */
    public List<TierTransition> decide(List<MemoryView> memories, long nowMs) {
        List<TierTransition> transitions = new java.util.ArrayList<>();
        if (memories == null) {
            return transitions;
        }
        for (MemoryView memory : memories) {
            boolean inCore = TIER_CORE.equals(memory.tier());
            if (!inCore && memory.accessCount() >= promoteThreshold) {
                transitions.add(new TierTransition(memory.memoryId(), memory.tier(),
                        TIER_CORE, "频次达标: " + memory.accessCount() + "≥" + promoteThreshold));
            } else if (inCore && memory.accessCount() < promoteThreshold
                    && nowMs - memory.lastAccessAt() >= idleMs) {
                transitions.add(new TierTransition(memory.memoryId(), memory.tier(),
                        TIER_ARCHIVE, "超期未访问: " + (nowMs - memory.lastAccessAt()) + "ms≥" + idleMs));
            }
        }
        return transitions;
    }

    public int promoteThreshold() {
        return promoteThreshold;
    }
}
