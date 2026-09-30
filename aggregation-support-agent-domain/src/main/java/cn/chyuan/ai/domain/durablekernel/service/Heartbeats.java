package cn.chyuan.ai.domain.durablekernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 心跳超时重试（工单 1070 EP5，temporal 思想）。
 * 心跳刷新到期时刻（now+timeout）/超时未心跳判失败并按重试策略计数重排/
 * 重试耗尽终态 FAILED/取消传播后不再心跳与超时（CANCELLED 终态）。
 */
public final class Heartbeats {

    /** 活动状态 */
    public enum State { RUNNING, FAILED, CANCELLED }

    private static final class Entry {
        long timeoutMs;
        int maxRetries;
        long expiry;
        int attempts;
        State state = State.RUNNING;
    }

    private final Map<String, Entry> activities = new HashMap<>();

    /** 调度活动：timeoutMs 超时窗 + maxRetries 最大重试次数 */
    public void schedule(String activityId, long timeoutMs, int maxRetries) {
        requireActivityId(activityId);
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("超时窗必须为正: " + timeoutMs);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("最大重试不可为负: " + maxRetries);
        }
        Entry entry = new Entry();
        entry.timeoutMs = timeoutMs;
        entry.maxRetries = maxRetries;
        entry.expiry = timeoutMs;
        activities.put(activityId, entry);
    }

    /** 心跳：刷新到期时刻 = now + timeout；未调度/终态拒绝 */
    public void heartbeat(String activityId, long nowMs) {
        Entry entry = requireRunning(activityId);
        entry.expiry = nowMs + entry.timeoutMs;
    }

    /** 推进时钟：到期未心跳判超时——重试次数内重排（attempt+1，到期顺延），耗尽转 FAILED */
    public void tick(long nowMs) {
        for (Entry entry : activities.values()) {
            if (entry.state != State.RUNNING || nowMs <= entry.expiry) {
                continue;
            }
            entry.attempts++;
            if (entry.attempts > entry.maxRetries) {
                entry.state = State.FAILED;
            } else {
                entry.expiry = nowMs + entry.timeoutMs;
            }
        }
    }

    /** 取消：停止心跳与超时判定 */
    public void cancel(String activityId) {
        Entry entry = activities.get(activityId);
        if (entry == null) {
            throw new IllegalArgumentException("未调度活动拒绝取消: " + activityId);
        }
        if (entry.state == State.FAILED) {
            throw new IllegalStateException("失败活动拒绝取消: " + activityId);
        }
        entry.state = State.CANCELLED;
    }

    public State state(String activityId) {
        return activities.get(activityId).state;
    }

    public int attempts(String activityId) {
        return activities.get(activityId).attempts;
    }

    private Entry requireRunning(String activityId) {
        Entry entry = activities.get(activityId);
        if (entry == null) {
            throw new IllegalArgumentException("未调度活动拒绝心跳: " + activityId);
        }
        if (entry.state != State.RUNNING) {
            throw new IllegalStateException("终态活动拒绝心跳: " + activityId + " " + entry.state);
        }
        return entry;
    }

    private void requireActivityId(String activityId) {
        if (activityId == null || activityId.isBlank()) {
            throw new IllegalArgumentException("activityId 不能为空");
        }
    }
}
