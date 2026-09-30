package cn.chyuan.ai.domain.durablekernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 事件历史（工单 1066 EP1，temporal 思想）。
 * append-only 追加/事件 id 自 1 连续递增/终态事件封板后拒绝再追加（中间态篡改拒绝）/
 * 历史只读快照（不可变事件 + 不可变列表）。
 */
public final class EventHistory {

    /** 事件：id 连续 + 类型 + 载荷（不可变值对象） */
    public record Event(long id, String type, String payload) {
    }

    public static final String WORKFLOW_STARTED = "WORKFLOW_STARTED";
    public static final String WORKFLOW_COMPLETED = "WORKFLOW_COMPLETED";
    public static final String ACTIVITY_COMPLETED = "ACTIVITY_COMPLETED";
    public static final String SIGNAL_RECEIVED = "SIGNAL_RECEIVED";
    public static final String TIMER_FIRED = "TIMER_FIRED";

    private final String workflowId;
    private final List<Event> events = new ArrayList<>();
    private boolean sealed;

    private EventHistory(String workflowId) {
        this.workflowId = workflowId;
    }

    /** 以 WORKFLOW_STARTED 起始创建历史 */
    public static EventHistory start(String workflowId, String input) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId 不能为空");
        }
        EventHistory history = new EventHistory(workflowId);
        history.events.add(new Event(1, WORKFLOW_STARTED, input == null ? "" : input));
        return history;
    }

    /** 追加事件；封板后（终态已落）拒绝 */
    public synchronized Event append(String type, String payload) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("事件类型不能为空");
        }
        if (sealed) {
            throw new IllegalStateException("历史已封板拒绝追加: " + workflowId);
        }
        Event event = new Event(events.size() + 1L, type, payload == null ? "" : payload);
        events.add(event);
        if (WORKFLOW_COMPLETED.equals(type)) {
            sealed = true;
        }
        return event;
    }

    /** 只读快照：不可变列表，篡改拒绝 */
    public List<Event> events() {
        return List.copyOf(events);
    }

    public Event last() {
        if (events.isEmpty()) {
            throw new IllegalStateException("空历史无末事件");
        }
        return events.get(events.size() - 1);
    }

    public boolean sealed() {
        return sealed;
    }

    public String workflowId() {
        return workflowId;
    }

    public Map<String, Object> summary() {
        return Map.of("workflowId", workflowId, "events", events.size(), "sealed", sealed);
    }
}
