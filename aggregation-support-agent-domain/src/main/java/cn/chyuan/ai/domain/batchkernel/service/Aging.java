package cn.chyuan.ai.domain.batchkernel.service;

/**
 * 老化防饿死（工单 1064 EO7，vllm 思想）。
 * 等待时长按阈值折算提权等级/提权等级有上限不无限/未达阈值不提权。
 */
public final class Aging {

    private final long thresholdTicks;
    private final int maxBoost;

    public Aging(long thresholdTicks, int maxBoost) {
        if (thresholdTicks <= 0) {
            throw new IllegalArgumentException("老化阈值必须为正: " + thresholdTicks);
        }
        if (maxBoost < 0) {
            throw new IllegalArgumentException("提权上限不可为负: " + maxBoost);
        }
        this.thresholdTicks = thresholdTicks;
        this.maxBoost = maxBoost;
    }

    /** 提权等级 = min(已等待 tick / 阈值, 上限) */
    public int boost(long arrivalTick, long nowTick) {
        long waited = Math.max(0, nowTick - arrivalTick);
        return (int) Math.min(waited / thresholdTicks, maxBoost);
    }
}
