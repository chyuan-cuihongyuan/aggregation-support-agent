package cn.chyuan.ai.domain.crawler.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 令牌桶节流（工单 0422 AY4）。
 * 每域独立令牌桶（容量/填充速率可配，时钟端口注入）；令牌按经过时间填充不超容量；
 * 未配置域共享默认桶；tryAcquire 输出成败与下次可取等待毫秒估算。纯函数内核。
 */
public class TokenBucketThrottle {

    /** 时钟端口 */
    public interface Clock {

        long nowMs();
    }

    /** 取令牌结果 */
    public record Attempt(boolean granted, long waitMs, double tokensLeft) {
    }

    private final int capacity;
    private final double refillPerSecond;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();

    private static final class Bucket {
        double tokens;
        long lastRefill;

        Bucket(double tokens, long lastRefill) {
            this.tokens = tokens;
            this.lastRefill = lastRefill;
        }
    }

    public TokenBucketThrottle(int capacity, double refillPerSecond, Clock clock) {
        if (capacity < 1 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("容量至少 1 且填充速率须为正");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.clock = clock;
    }

    /** 取一个令牌：拒绝时给出按速率估算的等待毫秒 */
    public Attempt tryAcquire(String domain) {
        synchronized (this) {
            long now = clock.nowMs();
            Bucket bucket = buckets.computeIfAbsent(domain, k -> new Bucket(capacity, now));
            refill(bucket, now);
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return new Attempt(true, 0, bucket.tokens);
            }
            long waitMs = Math.max(1, Math.round((1 - bucket.tokens) / refillPerSecond * 1000));
            return new Attempt(false, waitMs, bucket.tokens);
        }
    }

    /** 当前令牌余量（惰性填充后） */
    public double available(String domain) {
        synchronized (this) {
            long now = clock.nowMs();
            Bucket bucket = buckets.computeIfAbsent(domain, k -> new Bucket(capacity, now));
            refill(bucket, now);
            return bucket.tokens;
        }
    }

    private void refill(Bucket bucket, long now) {
        long elapsed = Math.max(0, now - bucket.lastRefill);
        if (elapsed > 0) {
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsed * refillPerSecond / 1000.0);
            bucket.lastRefill = now;
        }
    }
}
