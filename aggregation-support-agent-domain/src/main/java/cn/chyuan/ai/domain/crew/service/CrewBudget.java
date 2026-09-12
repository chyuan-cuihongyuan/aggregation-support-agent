package cn.chyuan.ai.domain.crew.service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 团队预算与上限（工单 0218 AC6，借鉴 AutoGPT 防跑飞）—
 * maxIterations 步数上限 + maxTotalTokens token 预算：执行器每步扣减，
 * 任一超限即截断（isExhausted）并产生 BUDGET_EXCEEDED 事件。
 *
 * @author chyuan
 */
public class CrewBudget {

    public static final String EVENT_BUDGET_EXCEEDED = "BUDGET_EXCEEDED";

    private final int maxIterations;
    private final long maxTotalTokens;
    private final AtomicLong iterations = new AtomicLong();
    private final AtomicLong tokensUsed = new AtomicLong();
    private volatile String exceededReason;

    public CrewBudget(int maxIterations, long maxTotalTokens) {
        this.maxIterations = Math.max(1, maxIterations);
        this.maxTotalTokens = Math.max(0, maxTotalTokens);
    }

    /** 单步扣减：步数 +1 与 token 消耗同时记账；返回是否仍在预算内 */
    public synchronized boolean consumeStep(long tokensOfStep) {
        iterations.incrementAndGet();
        tokensUsed.addAndGet(Math.max(0, tokensOfStep));
        if (iterations.get() > maxIterations) {
            exceededReason = "步数超限：" + iterations.get() + " > " + maxIterations;
            return false;
        }
        if (tokensUsed.get() > maxTotalTokens) {
            exceededReason = "token 超限：" + tokensUsed.get() + " > " + maxTotalTokens;
            return false;
        }
        return true;
    }

    /** 是否已超限（超限后恒 true） */
    public boolean isExhausted() {
        return exceededReason != null;
    }

    /** 超限原因（未超限返回 null） */
    public String exceededReason() {
        return exceededReason;
    }

    public long iterations() {
        return iterations.get();
    }

    public long tokensUsed() {
        return tokensUsed.get();
    }

    public int maxIterations() {
        return maxIterations;
    }

    public long maxTotalTokens() {
        return maxTotalTokens;
    }
}
