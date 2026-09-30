package cn.chyuan.ai.domain.durablekernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时器事件（工单 1072 EP7，temporal 思想）。
 * start-to-close 超时事件/heartbeat 超时事件/虚拟时钟 advance 到期触发（每定时器至多一次）/
 * 取消后不触发；到期后取消拒绝（已触发）。
 */
public final class Timers {

    /** 定时器触发：种类 + 关联引用 + 触发时刻 */
    public record TimerFire(String timerId, String kind, String ref, long firedAt) {
    }

    private static final class Entry {
        final String kind;
        final String ref;
        final long fireAt;
        boolean fired;
        boolean cancelled;

        Entry(String kind, String ref, long fireAt) {
            this.kind = kind;
            this.ref = ref;
            this.fireAt = fireAt;
        }
    }

    private final Map<String, Entry> timers = new HashMap<>();
    private long seq;

    /** start-to-close 超时定时器 */
    public String startToClose(String workflowId, long fireAtMs) {
        return create("START_TO_CLOSE", workflowId, fireAtMs);
    }

    /** heartbeat 超时定时器 */
    public String heartbeatTimeout(String activityId, long fireAtMs) {
        return create("HEARTBEAT_TIMEOUT", activityId, fireAtMs);
    }

    /** 推进虚拟时钟：返回本次到期触发的定时器事件（按到期时刻升序） */
    public List<TimerFire> advance(long nowMs) {
        List<TimerFire> fires = new ArrayList<>();
        List<Map.Entry<String, Entry>> due = new ArrayList<>();
        for (Map.Entry<String, Entry> entry : timers.entrySet()) {
            Entry timer = entry.getValue();
            if (!timer.fired && !timer.cancelled && timer.fireAt <= nowMs) {
                due.add(entry);
            }
        }
        due.sort((left, right) -> Long.compare(left.getValue().fireAt, right.getValue().fireAt));
        for (Map.Entry<String, Entry> entry : due) {
            entry.getValue().fired = true;
            fires.add(new TimerFire(entry.getKey(), entry.getValue().kind, entry.getValue().ref,
                    entry.getValue().fireAt));
        }
        return fires;
    }

    /** 取消定时器：此后不触发；已触发或已取消拒绝 */
    public void cancel(String timerId) {
        Entry timer = timers.get(timerId);
        if (timer == null) {
            throw new IllegalArgumentException("未知定时器拒绝取消: " + timerId);
        }
        if (timer.fired) {
            throw new IllegalStateException("已触发定时器拒绝取消: " + timerId);
        }
        if (timer.cancelled) {
            throw new IllegalStateException("重复取消拒绝: " + timerId);
        }
        timer.cancelled = true;
    }

    public int pending() {
        int count = 0;
        for (Entry timer : timers.values()) {
            if (!timer.fired && !timer.cancelled) {
                count++;
            }
        }
        return count;
    }

    private String create(String kind, String ref, long fireAtMs) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("定时器关联引用不能为空");
        }
        String timerId = "timer-" + (++seq);
        timers.put(timerId, new Entry(kind, ref, fireAtMs));
        return timerId;
    }
}
