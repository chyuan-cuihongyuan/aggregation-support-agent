package cn.chyuan.ai.domain.durablekernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 持久执行编排实现（工单 1073 EP8，temporal 思想）。
 * start 创建事件历史并在线驱动代码至终态或前沿；signal 经 Signals 校验后事件化入史；
 * query 只读摘要；replay 快照重放校验确定性。
 */
public final class DurableHub implements DurablePort {

    private final int maxSteps;
    private final Map<String, EventHistory> histories = new HashMap<>();
    private final Signals signals = new Signals();

    public DurableHub(int maxSteps) {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("步数上限必须为正: " + maxSteps);
        }
        this.maxSteps = maxSteps;
    }

    @Override
    public String start(String workflowId, String input, Replayer.WorkflowCode code) {
        if (histories.containsKey(workflowId)) {
            throw new IllegalArgumentException("重复 workflowId 拒绝: " + workflowId);
        }
        if (code == null) {
            throw new IllegalArgumentException("工作流代码不能为空");
        }
        EventHistory history = EventHistory.start(workflowId, input);
        histories.put(workflowId, history);
        signals.open(workflowId);
        String output = Replayer.runOnline(history, code, maxSteps);
        if (output != null) {
            signals.close(workflowId);
            return "COMPLETED:" + output;
        }
        return "RUNNING";
    }

    @Override
    public String replay(String workflowId, Replayer.WorkflowCode code) {
        EventHistory history = histories.get(workflowId);
        if (history == null) {
            throw new IllegalArgumentException("未知 workflowId 拒绝: " + workflowId);
        }
        String output = Replayer.replay(history.events(), code, maxSteps);
        return output == null ? "RUNNING" : "COMPLETED:" + output;
    }

    @Override
    public List<String> history(String workflowId) {
        EventHistory history = requireHistory(workflowId);
        List<String> lines = new ArrayList<>();
        for (EventHistory.Event event : history.events()) {
            lines.add(event.id() + ":" + event.type() + ":" + event.payload());
        }
        return lines;
    }

    @Override
    public void signal(String workflowId, String name, String payload) {
        EventHistory history = requireHistory(workflowId);
        Signals.SignalEvent event = signals.signal(workflowId, name, payload);
        history.append(EventHistory.SIGNAL_RECEIVED, event.name() + ":" + event.payload());
    }

    @Override
    public List<String> query(String workflowId) {
        EventHistory history = requireHistory(workflowId);
        List<String> summary = new ArrayList<>();
        summary.add("state=" + (history.sealed() ? "COMPLETED" : "RUNNING"));
        summary.add("events=" + history.events().size());
        summary.add("signals=" + signals.query(workflowId).size());
        return summary;
    }

    @Override
    public List<String> checkpointShape() {
        return List.of("runId", "seq", "pendingNodeId", "completedNodeIds", "createdAt");
    }

    private EventHistory requireHistory(String workflowId) {
        EventHistory history = histories.get(workflowId);
        if (history == null) {
            throw new IllegalArgumentException("未知 workflowId 拒绝: " + workflowId);
        }
        return history;
    }
}
