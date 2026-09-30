package cn.chyuan.ai.domain.batchkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * chunked prefill 分块（工单 1063 EO6，vllm 思想）。
 * 长 prefill 按块预算切分/每块钳制不超块预算/总和恰等于声明 token/非正块预算拒绝。
 */
public final class ChunkedPrefill {

    private final long chunkBudget;

    public ChunkedPrefill(long chunkBudget) {
        if (chunkBudget <= 0) {
            throw new IllegalArgumentException("块预算必须为正: " + chunkBudget);
        }
        this.chunkBudget = chunkBudget;
    }

    /** 是否需要分块：声明 token 超块预算即分块 */
    public boolean chunked(long declaredTokens) {
        return declaredTokens > chunkBudget;
    }

    /** 切分计划：每块 ≤ 块预算，末块收敛，总和 = 声明 token */
    public List<Long> plan(long declaredTokens) {
        if (declaredTokens <= 0) {
            throw new IllegalArgumentException("声明 token 必须为正: " + declaredTokens);
        }
        List<Long> chunks = new ArrayList<>();
        long remaining = declaredTokens;
        while (remaining > 0) {
            long take = Math.min(remaining, chunkBudget);
            chunks.add(take);
            remaining -= take;
        }
        return chunks;
    }
}
