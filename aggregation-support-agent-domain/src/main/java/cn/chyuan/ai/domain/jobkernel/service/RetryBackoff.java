package cn.chyuan.ai.domain.jobkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 失败重试退避（工单 0501 BH6，xxl-job 重试思想）。
 * 指数退避（base×2^n）+ 抖动（随机端口注入）/最大重试次数上限/
 * 耗尽后死信任务登记（任务 id+失败原因+最后尝试时刻）。
 */
public class RetryBackoff {

    /** 时钟端口 */
    @FunctionalInterface
    public interface ClockPort {
        long nowMillis();
    }

    /** 随机端口（抖动用，注入可复现） */
    @FunctionalInterface
    public interface RandomPort {
        double nextDouble();
    }

    /** 死信任务 */
    public record DeadLetter(String taskId, String reason, long lastAttemptAt) {
    }

    private final long baseDelayMillis;
    private final int maxRetries;
    private final double jitterRatio;
    private final ClockPort clock;
    private final RandomPort random;
    private final List<DeadLetter> deadLetters = new ArrayList<>();

    public RetryBackoff(long baseDelayMillis, int maxRetries, double jitterRatio,
            ClockPort clock, RandomPort random) {
        if (baseDelayMillis < 0 || maxRetries < 0 || jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("参数非法: base ≥ 0, maxRetries ≥ 0, 0 ≤ jitter ≤ 1");
        }
        this.baseDelayMillis = baseDelayMillis;
        this.maxRetries = maxRetries;
        this.jitterRatio = jitterRatio;
        this.clock = clock;
        this.random = random;
    }

    /** 第 attempt 次失败后的下次重试延迟（attempt 从 1 起；耗尽返回 -1 并登记死信） */
    public synchronized long nextDelay(String taskId, int attempt, String reason) {
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt 须 > 0");
        }
        if (attempt > maxRetries) {
            deadLetters.add(new DeadLetter(taskId, reason, clock.nowMillis()));
            return -1L;
        }
        long exponential = baseDelayMillis * (1L << (attempt - 1));
        long jitter = (long) (exponential * jitterRatio * (random.nextDouble() * 2 - 1));
        return Math.max(0, exponential + jitter);
    }

    /** 死信清单 */
    public synchronized List<DeadLetter> deadLetters() {
        return List.copyOf(deadLetters);
    }
}
