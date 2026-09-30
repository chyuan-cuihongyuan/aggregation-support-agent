package cn.chyuan.ai.domain.batchkernel.service;

import java.util.List;

/**
 * 推理批调度端口（工单 1065 EO8，vllm 思想）。
 * submit·step·complete 入口统一编排：请求生命周期·等待准入·迭代级连续批·
 * 抢占重算·chunked prefill·老化防饿死组合管线/inferkernel 请求参数形状只读联动
 * （形状键与 InferPort.Request 字段对齐，不 import inferkernel）/
 * batch-kernel.enabled 默认关（开启才改变行为）。
 */
public interface BatchPort {

    // —— 请求生命周期（EO1）——
    void submit(String id, long declaredTokens);

    String state(String id);

    void complete(String id);

    void fail(String id);

    // —— 准入与迭代推进（EO2/EO3/EO5/EO6）——
    int step();

    List<String> batch();

    long usedTokens();

    long tick();

    // —— 抢占（EO4）——
    int preemptions(String id);

    // —— inferkernel 形状只读联动（EO8）——
    List<String> optionsShape();

    static BatchPort inMemory(long tokenBudget, int maxBatchSize, long chunkBudget, long agingThresholdTicks, int agingMaxBoost) {
        return new BatchScheduler(tokenBudget, maxBatchSize, chunkBudget, agingThresholdTicks, agingMaxBoost);
    }
}
