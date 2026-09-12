package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;

import java.util.List;
import java.util.Map;

/**
 * 工作流运行记录值对象（工单 0212 AB9）— run 级摘要 + 节点级明细（JSON 文本落库）。
 *
 * @author chyuan
 */
public record WorkflowRunRecord(String runId, String workflowName, int workflowVersion, String tenantId,
        String status, String failedNodeId, String error, long durationMs,
        List<NodeRunEntry> nodeRuns, long createdAt) {

    /** 节点级明细 */
    public record NodeRunEntry(String nodeId, String status, int attempts, long durationMs, String error) {
    }

    public WorkflowRunRecord {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        nodeRuns = nodeRuns == null ? List.of() : List.copyOf(nodeRuns);
    }

    /** 从执行报告构造（含触发信息） */
    public static WorkflowRunRecord from(GraphExecutor.ExecutionReport report, WorkflowGraph graph,
            int version, String tenantId, long durationMs) {
        return new WorkflowRunRecord(report.runId(), graph.name(), version, tenantId, report.status(),
                report.failedNodeId(), report.error(), durationMs,
                report.nodeRuns().stream()
                        .map(n -> new NodeRunEntry(n.nodeId(), n.status(), n.attempts(), n.durationMs(),
                                n.error()))
                        .toList(),
                System.currentTimeMillis());
    }

    /** 节点明细序列化（落库 JSON 文本） */
    public String nodeRunsJson() {
        com.alibaba.fastjson.JSONArray array = new com.alibaba.fastjson.JSONArray();
        for (NodeRunEntry node : nodeRuns) {
            com.alibaba.fastjson.JSONObject o = new com.alibaba.fastjson.JSONObject(true);
            o.put("nodeId", node.nodeId());
            o.put("status", node.status());
            o.put("attempts", node.attempts());
            o.put("durationMs", node.durationMs());
            o.put("error", node.error());
            array.add(o);
        }
        return array.toJSONString();
    }

    /** 附加元数据视图（端点响应组装） */
    public Map<String, Object> toMap() {
        return Map.of(
                "runId", runId,
                "workflowName", workflowName,
                "workflowVersion", workflowVersion,
                "status", status,
                "failedNodeId", failedNodeId == null ? "" : failedNodeId,
                "error", error == null ? "" : error,
                "durationMs", durationMs,
                "nodeCount", nodeRuns.size());
    }
}
