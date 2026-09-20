package cn.chyuan.ai.domain.jobkernel.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 并发控制与超时（工单 0500 BH5，xxl-job 阻塞策略思想）。
 * 同任务互斥（运行中拒绝新触发）/超时中断判定（时钟端口）/阻塞策略三选
 * （DISCARD 丢弃/QUEUE 排队/OVERRIDE 覆盖）。
 */
public class ConcurrencyGuard {

    /** 时钟端口 */
    @FunctionalInterface
    public interface ClockPort {
        long nowMillis();
    }

    /** 阻塞策略 */
    public enum BlockStrategy {
        DISCARD, QUEUE, OVERRIDE
    }

    /** 触发裁决 */
    public enum Decision {
        RUN, DISCARDED, QUEUED, OVERRIDE_RUNNING
    }

    private final ClockPort clock;
    private final long timeoutMillis;
    private final Map<String, Long> runningSince = new HashMap<>();
    private final Map<String, Set<Long>> queuedTriggers = new HashMap<>();

    public ConcurrencyGuard(ClockPort clock, long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("超时时限须 > 0");
        }
        this.clock = clock;
        this.timeoutMillis = timeoutMillis;
    }

    /** 触发裁决：空闲 → RUN；运行中按阻塞策略 */
    public synchronized Decision onTrigger(String taskId, BlockStrategy strategy, long triggerPoint) {
        if (!isRunning(taskId)) {
            runningSince.put(taskId, clock.nowMillis());
            return Decision.RUN;
        }
        return switch (strategy) {
            case DISCARD -> Decision.DISCARDED;
            case QUEUE -> {
                queuedTriggers.computeIfAbsent(taskId, k -> new HashSet<>()).add(triggerPoint);
                yield Decision.QUEUED;
            }
            case OVERRIDE -> {
                runningSince.put(taskId, clock.nowMillis());
                yield Decision.OVERRIDE_RUNNING;
            }
        };
    }

    /** 运行中判定（含超时中断：超时任务视为已死可重入） */
    public synchronized boolean isRunning(String taskId) {
        Long since = runningSince.get(taskId);
        if (since == null) {
            return false;
        }
        if (clock.nowMillis() - since > timeoutMillis) {
            runningSince.remove(taskId);
            return false;
        }
        return true;
    }

    /** 任务完成：释放互斥并取回排队触发点 */
    public synchronized Long complete(String taskId) {
        runningSince.remove(taskId);
        Set<Long> queue = queuedTriggers.remove(taskId);
        return queue == null || queue.isEmpty() ? null : queue.iterator().next();
    }

    /** 超时中断判定：超时返回 true 并释放 */
    public synchronized boolean timedOut(String taskId) {
        Long since = runningSince.get(taskId);
        return since != null && clock.nowMillis() - since > timeoutMillis;
    }
}
