package cn.chyuan.ai.infrastructure.gateway.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 输出守卫契约测试（SELFLOOP2 loop-229）：
 * 重试环 / 全败回退空 / 异常计失败 / 共享校验谓词。
 */
@DisplayName("LlmOutputGuard 输出守卫契约")
class LlmOutputGuardTest {

    private final LlmOutputGuard guard = new LlmOutputGuard();
    private final Predicate<String> validator = LlmOutputGuard.NON_BLANK_MAX_500;

    @Test
    @DisplayName("前两次无效、第三次有效 → 返回有效值（共尝试 3 次）")
    void retriesUntilValid() {
        AtomicInteger calls = new AtomicInteger();
        Optional<String> result = guard.callUntilValid(() -> {
            int n = calls.incrementAndGet();
            return n < 3 ? "  " : "有效改写结果";
        }, validator);

        assertTrue(result.isPresent());
        assertEquals("有效改写结果", result.get());
        assertEquals(3, calls.get());
    }

    @Test
    @DisplayName("全部无效 → empty（调用方回退）")
    void allInvalidReturnsEmpty() {
        AtomicInteger calls = new AtomicInteger();
        Optional<String> result = guard.callUntilValid(() -> {
            calls.incrementAndGet();
            return null;
        }, validator, 3);

        assertFalse(result.isPresent());
        assertEquals(3, calls.get());
    }

    @Test
    @DisplayName("Supplier 异常计为一次失败尝试，不中断重试")
    void exceptionCountsAsAttempt() {
        AtomicInteger calls = new AtomicInteger();
        Optional<String> result = guard.callUntilValid(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("模型超时");
            }
            return "第二次成功";
        }, validator, 3);

        assertTrue(result.isPresent());
        assertEquals("第二次成功", result.get());
    }

    @Test
    @DisplayName("共享校验谓词：空白/超长拒绝，正常通过并 trim")
    void sharedPredicate() {
        assertTrue(LlmOutputGuard.NON_BLANK_MAX_500.test("正常查询"));
        assertFalse(LlmOutputGuard.NON_BLANK_MAX_500.test("   "));
        assertFalse(LlmOutputGuard.NON_BLANK_MAX_500.test(null));
        assertFalse(LlmOutputGuard.NON_BLANK_MAX_500.test("长".repeat(501)));
        // 输出侧 trim：首尾空白去除后仍需过校验
        Optional<String> trimmed = guard.callUntilValid(() -> "  带空白的结果  ", validator);
        assertEquals("带空白的结果", trimmed.orElseThrow());
    }
}
