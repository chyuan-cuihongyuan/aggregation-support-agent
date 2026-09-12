package cn.chyuan.ai.infrastructure.gateway.query;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * LLM 输出守卫（SELFLOOP2 loop-229，J02：借鉴 Spring AI Recursive Advisors 的
 * 「校验失败重问」环模式）。
 * <p>
 * 对 LLM 输出做谓词校验，失败重试至 maxAttempts；Supplier 抛异常视为一次失败尝试；
 * 全部失败返回 empty，由调用方决定回退（如返回原始查询）。
 */
@Slf4j
@Component
public class LlmOutputGuard {

    /** 重试次数上限（含首次） */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** 共享校验：非空非空白且不超过 500 字符（查询改写类输出的合理上限） */
    public static final Predicate<String> NON_BLANK_MAX_500 =
            s -> s != null && !s.isBlank() && s.length() <= 500;

    public Optional<String> callUntilValid(Supplier<String> call, Predicate<String> validator) {
        return callUntilValid(call, validator, DEFAULT_MAX_ATTEMPTS);
    }

    public Optional<String> callUntilValid(Supplier<String> call, Predicate<String> validator, int maxAttempts) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String output;
            try {
                output = call.get();
            } catch (Exception e) {
                log.warn("LLM 调用异常（第 {}/{} 次尝试）: {}", attempt, maxAttempts, e.getMessage());
                continue;
            }
            if (validator.test(output)) {
                if (attempt > 1) {
                    log.info("LLM 输出在第 {} 次尝试后通过校验", attempt);
                }
                return Optional.of(output.trim());
            }
            log.warn("LLM 输出未过校验（第 {}/{} 次尝试），重试", attempt, maxAttempts);
        }
        log.warn("LLM 输出连续 {} 次未通过校验，交由调用方回退", maxAttempts);
        return Optional.empty();
    }
}
