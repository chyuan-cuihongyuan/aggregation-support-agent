package cn.chyuan.ai.domain.workflow.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试策略单测（工单 0209 AB6）：指数退避/上限/抖动/config 解析与脏值回退。
 */
class RetryPolicyTest {

    @Test
    void 指数退避序列与上限() {
        RetryPolicy policy = new RetryPolicy(5, 100, 1000, 0);
        assertEquals(100, RetryPolicy.BackoffCalculator.nominalDelayMs(policy, 1));
        assertEquals(200, RetryPolicy.BackoffCalculator.nominalDelayMs(policy, 2));
        assertEquals(400, RetryPolicy.BackoffCalculator.nominalDelayMs(policy, 3));
        assertEquals(800, RetryPolicy.BackoffCalculator.nominalDelayMs(policy, 4));
        // 封顶
        assertEquals(1000, RetryPolicy.BackoffCalculator.nominalDelayMs(policy, 5));
        assertEquals(1000, RetryPolicy.BackoffCalculator.nominalDelayMs(policy, 9));
        // attempt<1 视为 1
        assertEquals(100, RetryPolicy.BackoffCalculator.nominalDelayMs(policy, 0));
    }

    @Test
    void 抖动区间与确定性() {
        RetryPolicy policy = new RetryPolicy(3, 200, 30_000, 0.5d);
        // random=0 → 名义-50%；0.5 → 名义；趋 1 → 名义+50%（区间断言，容忍截断）
        assertEquals(100, RetryPolicy.BackoffCalculator.delayWithJitterMs(policy, 1, () -> 0.0d));
        assertEquals(200, RetryPolicy.BackoffCalculator.delayWithJitterMs(policy, 1, () -> 0.5d));
        long nearHigh = RetryPolicy.BackoffCalculator.delayWithJitterMs(policy, 1, () -> 0.999999d);
        assertTrue(nearHigh >= 299 && nearHigh <= 300, "趋高端应接近名义+50%，实际 " + nearHigh);
        // 零抖动回名义值
        assertEquals(200, RetryPolicy.BackoffCalculator.delayWithJitterMs(
                new RetryPolicy(3, 200, 30_000, 0), 1, () -> 0.9d));
        // 负值钳制 0
        assertTrue(RetryPolicy.BackoffCalculator.delayWithJitterMs(policy, 1, () -> 0.0d) >= 0);
    }

    @Test
    void config解析与脏值回退() {
        assertEquals(5, RetryPolicy.fromConfig(Map.of("retry.max-attempts", "5")).maxAttempts());
        // 部分配置 + 非法值整体回退默认
        assertEquals(RetryPolicy.DEFAULT, RetryPolicy.fromConfig(Map.of("retry.max-attempts", "abc")));
        // 空配置 → 默认
        assertEquals(RetryPolicy.DEFAULT, RetryPolicy.fromConfig(Map.of()));
        // 归一化：非法 maxAttempts/maxBackoff 回退
        assertEquals(1, new RetryPolicy(0, 100, 50, 2).maxAttempts());
        assertEquals(100, new RetryPolicy(2, 100, 50, 2).maxBackoffMs());
        assertEquals(0, new RetryPolicy(2, 100, 1000, 9).jitterRatio());
    }
}
