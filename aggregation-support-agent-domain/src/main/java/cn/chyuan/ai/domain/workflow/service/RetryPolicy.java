package cn.chyuan.ai.domain.workflow.service;

import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * 节点重试策略（工单 0209 AB6，借鉴 Temporal retry policy）—
 * 指数退避 + 抖动：delay(attempt) = min(base * 2^(attempt-1), maxBackoff)，
 * 抖动幅度 = delay * jitterRatio（± 内均匀）。值对象 + 计算器分离保证纯函数可测。
 *
 * @author chyuan
 */
public record RetryPolicy(int maxAttempts, long baseBackoffMs, long maxBackoffMs, double jitterRatio) {

    /** 默认策略：3 次 / 200ms 基底 / 30s 上限 / 20% 抖动 */
    public static final RetryPolicy DEFAULT = new RetryPolicy(3, 200, 30_000, 0.2d);

    public RetryPolicy {
        if (maxAttempts < 1) {
            maxAttempts = 1;
        }
        if (baseBackoffMs < 0) {
            baseBackoffMs = 0;
        }
        if (maxBackoffMs < baseBackoffMs) {
            maxBackoffMs = baseBackoffMs;
        }
        if (jitterRatio < 0 || jitterRatio > 1) {
            jitterRatio = 0;
        }
    }

    /** 从节点 config 解析（缺省回退默认值；非法值回退默认） */
    public static RetryPolicy fromConfig(Map<String, String> config) {
        if (config == null || config.isEmpty()) {
            return DEFAULT;
        }
        try {
            int attempts = parse(config.get("retry.max-attempts"), DEFAULT.maxAttempts());
            long base = parse(config.get("retry.base-backoff-ms"), DEFAULT.baseBackoffMs());
            long max = parse(config.get("retry.max-backoff-ms"), DEFAULT.maxBackoffMs());
            double jitter = config.containsKey("retry.jitter-ratio")
                    ? Double.parseDouble(config.get("retry.jitter-ratio")) : DEFAULT.jitterRatio();
            return new RetryPolicy(attempts, base, max, jitter);
        } catch (NumberFormatException e) {
            return DEFAULT;
        }
    }

    private static int parse(String value, int fallback) {
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static long parse(String value, long fallback) {
        return value == null ? fallback : Long.parseLong(value);
    }

    /** 退避计算器（attempt 从 1 计） */
    public static final class BackoffCalculator {

        private BackoffCalculator() {
        }

        /** 无抖动的名义延迟 */
        public static long nominalDelayMs(RetryPolicy policy, int attempt) {
            if (attempt < 1) {
                attempt = 1;
            }
            long delay = policy.baseBackoffMs();
            for (int i = 1; i < attempt; i++) {
                delay = Math.min(delay * 2, policy.maxBackoffMs());
            }
            return Math.min(delay, policy.maxBackoffMs());
        }

        /**
         * 含抖动延迟：delay ± jitterRatio*delay 区间内均匀取值；
         * random 注入 [0,1) 保证测试确定性（0 → 最低，0.5 → 名义值，趋 1 → 最高）。
         */
        public static long delayWithJitterMs(RetryPolicy policy, int attempt, DoubleSupplier random) {
            long nominal = nominalDelayMs(policy, attempt);
            if (policy.jitterRatio() <= 0) {
                return nominal;
            }
            double r = random.getAsDouble();
            double spread = nominal * policy.jitterRatio();
            return Math.max(0, (long) (nominal - spread + 2 * spread * r));
        }
    }
}
