package cn.chyuan.ai.domain.agent.service.armory.node;

import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * LLM 重试模板工厂（SELFLOOP4 loop-412，工单 0622/0623）。
 * <p>
 * 独立于 ChatModelNode 继承链（根 pom surefire 排除 wrench 框架出测试类路径，
 * 工厂单放以便纯单测）。显式装配语义对照 Spring AI 官方 spring.ai.retry 属性表，
 * 交互口径收敛 3 次、退避上限 10s 消灭长尾（调研见 docs/research/2026-09-java-resilience-llm-retry.md）。
 */
final class LlmRetrySupport {

    private LlmRetrySupport() {
    }

    /**
     * 构建显式 RetryTemplate：
     * 次数钳 [1,10]、initial >=100ms、max-interval >= initial，防配置误用放大故障。
     */
    static RetryTemplate buildRetryTemplate(int maxAttempts, long initialIntervalMs,
                                            double multiplier, long maxIntervalMs) {
        int attempts = Math.max(1, Math.min(10, maxAttempts));
        long initial = Math.max(100, initialIntervalMs);
        long maxInterval = Math.max(initial, maxIntervalMs);

        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(initial);
        backoff.setMultiplier(multiplier);
        backoff.setMaxInterval(maxInterval);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(attempts));
        retryTemplate.setBackOffPolicy(backoff);
        return retryTemplate;
    }
}
