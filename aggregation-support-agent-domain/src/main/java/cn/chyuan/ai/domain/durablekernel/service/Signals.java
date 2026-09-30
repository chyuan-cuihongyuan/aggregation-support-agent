package cn.chyuan.ai.domain.durablekernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * signal 事件化与 query 只读（工单 1071 EP6，temporal 思想）。
 * signal 作为事件记录（重复 signal 各自独立）/未开启（未 start 或已终态）workflow 的 signal 拒绝/
 * query 只读不产生事件（事件数不变）。
 */
public final class Signals {

    /** 已记录的 signal 事件载荷 */
    public record SignalEvent(String workflowId, String name, String payload, int ordinal) {
    }

    private final Map<String, List<SignalEvent>> received = new HashMap<>();
    private final Map<String, Boolean> active = new HashMap<>();

    /** workflow 开启（start 后可接收 signal） */
    public void open(String workflowId) {
        requireWorkflowId(workflowId);
        active.put(workflowId, true);
        received.putIfAbsent(workflowId, new ArrayList<>());
    }

    /** workflow 终态关闭（signal 拒绝） */
    public void close(String workflowId) {
        active.put(workflowId, false);
    }

    /** 记录 signal 事件；未开启拒绝 */
    public SignalEvent signal(String workflowId, String name, String payload) {
        requireWorkflowId(workflowId);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("signal 名不能为空");
        }
        if (!Boolean.TRUE.equals(active.get(workflowId))) {
            throw new IllegalStateException("未开启 workflow 的 signal 拒绝: " + workflowId);
        }
        List<SignalEvent> events = received.get(workflowId);
        SignalEvent event = new SignalEvent(workflowId, name, payload == null ? "" : payload,
                events.size() + 1);
        events.add(event);
        return event;
    }

    /** query 只读：返回 signal 摘要，不记录任何事件 */
    public List<String> query(String workflowId) {
        requireWorkflowId(workflowId);
        List<SignalEvent> events = received.get(workflowId);
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<String> summary = new ArrayList<>();
        for (SignalEvent event : events) {
            summary.add(event.name() + ":" + event.payload());
        }
        return summary;
    }

    public int count(String workflowId) {
        List<SignalEvent> events = received.get(workflowId);
        return events == null ? 0 : events.size();
    }

    private void requireWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId 不能为空");
        }
    }
}
