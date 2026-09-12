package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;

import java.util.Map;

/**
 * 工作流检查点值对象（工单 0206 AB3）— 节点级快照：图定义 + 已完成节点集 +
 * 上下文快照 + 序号；恢复时从 pendingNode 断点续跑。
 *
 * @author chyuan
 */
public record Checkpoint(String runId, long seq, WorkflowGraph graph, String pendingNodeId,
        java.util.Set<String> completedNodeIds, Map<String, Object> contextSnapshot, long createdAt) {

    public Checkpoint {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        completedNodeIds = completedNodeIds == null ? java.util.Set.of() : java.util.Set.copyOf(completedNodeIds);
        contextSnapshot = contextSnapshot == null ? java.util.Map.of() : java.util.Map.copyOf(contextSnapshot);
    }
}
