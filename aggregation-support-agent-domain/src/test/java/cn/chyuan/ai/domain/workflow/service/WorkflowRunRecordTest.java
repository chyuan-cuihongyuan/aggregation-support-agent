package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行历史单测（工单 0212 AB9）：记录构造/落存/查询/节点明细 JSON。
 */
class WorkflowRunRecordTest {

    @Test
    void 运行记录构造与查询() {
        WorkflowGraph g = WorkflowGraph.builder("w")
                .node("a", "TASK", Map.of())
                .node("b", "TASK", Map.of())
                .edge("a", "b").build();
        GraphExecutor executor = new GraphExecutor(null, null, ms -> {
        });
        GraphExecutor.ExecutionReport report = executor.execute(g, Map.of(), (node, ctx) -> node.id());
        WorkflowRunRecord record = WorkflowRunRecord.from(report, g, 1, "tenant-a",
                report.nodeRuns().size() * 5L);
        assertEquals(GraphExecutor.STATUS_COMPLETED, record.status());
        assertEquals(2, record.nodeRuns().size());
        assertEquals("tenant-a", record.tenantId());

        InMemoryWorkflowRunStore store = new InMemoryWorkflowRunStore();
        store.saveRun(record);
        assertNotNull(store.findRun(report.runId()));
        assertNull(store.findRun("ghost"));
        assertEquals(1, store.recentRuns("w", 10).size());
        assertTrue(store.recentRuns("w", 10).get(0).nodeRunsJson().contains("\"nodeId\":\"a\""));
        assertEquals(2, record.toMap().get("nodeCount"));
        // 按名过滤 + limit 生效
        assertEquals(0, store.recentRuns("other", 10).size());
        assertEquals(1, store.recentRuns(null, 10).size());
    }

    @Test
    void 失败记录留痕() {
        WorkflowGraph g = WorkflowGraph.builder("w2")
                .node("a", "TASK", Map.of("retry.max-attempts", "2", "retry.base-backoff-ms", "0"))
                .build();
        GraphExecutor executor = new GraphExecutor(null, null, ms -> {
        });
        GraphExecutor.ExecutionReport report = executor.execute(g, Map.of(), (node, ctx) -> {
            throw new IllegalStateException("boom");
        });
        WorkflowRunRecord record = WorkflowRunRecord.from(report, g, 1, null, 9L);
        assertEquals(GraphExecutor.STATUS_FAILED, record.status());
        assertEquals("a", record.failedNodeId());
        assertEquals(2, record.nodeRuns().get(0).attempts());
        assertTrue(record.nodeRunsJson().contains("boom"));
    }
}
