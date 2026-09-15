package cn.chyuan.ai.domain.agent.service.armory.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LlmRetrySupport 行为单测（SELFLOOP4 loop-412，工单 0622/0623）。
 * 以「真实执行计数」验证重试语义（当前 spring-retry 版本无策略 getter）。
 */
class LlmRetrySupportTest {

    private int executeFailing(RetryTemplate rt) {
        AtomicInteger attempts = new AtomicInteger();
        try {
            rt.execute(ctx -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            // 重试耗尽后抛出，属预期
        }
        return attempts.get();
    }

    @Test
    @DisplayName("显式参数生效：maxAttempts=3 恰好执行 3 次（1ms 退避不拖慢）")
    void explicitAttemptsExecuted() {
        assertEquals(3, executeFailing(LlmRetrySupport.buildRetryTemplate(3, 1, 1.0, 1)));
    }

    @Test
    @DisplayName("钳位：0→1 次、15→10 次")
    void clampedAgainstMisuse() {
        assertEquals(1, executeFailing(LlmRetrySupport.buildRetryTemplate(0, 1, 1.0, 1)));
        assertEquals(10, executeFailing(LlmRetrySupport.buildRetryTemplate(15, 1, 1.0, 1)));
    }
}
