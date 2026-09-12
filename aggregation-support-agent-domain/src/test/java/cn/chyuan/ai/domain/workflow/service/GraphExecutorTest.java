package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.adapter.port.ICheckpointStore;
import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行引擎单测（工单 0205-0207/0209 AB2/AB3/AB4/AB6）：
 * 拓扑执行/上下文传递/失败短路/检查点续跑/中断恢复/重试。
 */
class GraphExecutorTest {

    private static final GraphExecutor.Sleeper NO_SLEEP = ms -> {
    };

    @Test
    void 线性执行与上下文传递() {
        WorkflowGraph g = WorkflowGraph.builder("lin")
                .node("a", "TASK", Map.of())
                .node("b", "TASK", Map.of())
                .edge("a", "b").build();
        GraphExecutor executor = new GraphExecutor(null, null, NO_SLEEP);
        List<String> visited = new ArrayList<>();
        GraphExecutor.ExecutionReport report = executor.execute(g, Map.of("input", "hi"), (node, ctx) -> {
            visited.add(node.id());
            if (node.id().equals("a")) {
                return String.valueOf(ctx.get("input")) + "-a";
            }
            return ctx.getString("a.output") + "-b";
        });
        assertEquals(GraphExecutor.STATUS_COMPLETED, report.status());
        assertEquals(List.of("a", "b"), visited);
        assertEquals(List.of("SUCCESS", "SUCCESS"),
                report.nodeRuns().stream().map(GraphExecutor.NodeRun::status).toList());
    }

    @Test
    void 分叉汇聚执行顺序() {
        WorkflowGraph g = WorkflowGraph.builder("diamond")
                .node("s", "TASK", Map.of())
                .node("l", "TASK", Map.of())
                .node("r", "TASK", Map.of())
                .node("t", "TASK", Map.of())
                .edge("s", "l").edge("s", "r").edge("l", "t").edge("r", "t").build();
        GraphExecutor executor = new GraphExecutor(null, null, NO_SLEEP);
        GraphExecutor.ExecutionReport report = executor.execute(g, Map.of(), (node, ctx) -> node.id());
        assertEquals(4, report.nodeRuns().size());
        // t 最后执行
        assertEquals("t", report.nodeRuns().get(3).nodeId());
    }

    @Test
    void 失败短路下游不执行() {
        WorkflowGraph g = WorkflowGraph.builder("short")
                .node("a", "TASK", Map.of())
                .node("b", "TASK", Map.of())
                .node("c", "TASK", Map.of())
                .edge("a", "b").edge("b", "c").build();
        GraphExecutor executor = new GraphExecutor(null, null, NO_SLEEP);
        AtomicInteger cCalls = new AtomicInteger();
        GraphExecutor.ExecutionReport report = executor.execute(g, Map.of(), (node, ctx) -> {
            if (node.id().equals("b")) {
                throw new IllegalStateException("boom");
            }
            if (node.id().equals("c")) {
                cCalls.incrementAndGet();
            }
            return node.id();
        });
        assertEquals(GraphExecutor.STATUS_FAILED, report.status());
        assertEquals("b", report.failedNodeId());
        assertEquals(0, cCalls.get());
        assertTrue(report.error().contains("boom"));
    }

    @Test
    void 重试策略耗尽与恢复() {
        WorkflowGraph g = WorkflowGraph.builder("retry")
                .node("a", "TASK", Map.of("retry.max-attempts", "3", "retry.base-backoff-ms", "0"))
                .build();
        GraphExecutor executor = new GraphExecutor(null, null, NO_SLEEP);
        // 前两次失败第三次成功
        AtomicInteger attempts = new AtomicInteger();
        GraphExecutor.ExecutionReport ok = executor.execute(g, Map.of(), (node, ctx) -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("transient");
            }
            return "done";
        });
        assertEquals(GraphExecutor.STATUS_COMPLETED, ok.status());
        assertEquals(3, ok.nodeRuns().get(0).attempts());
        // 永久失败耗尽
        GraphExecutor.ExecutionReport fail = executor.execute(g, Map.of(), (node, ctx) -> {
            throw new IllegalStateException("always");
        });
        assertEquals(GraphExecutor.STATUS_FAILED, fail.status());
        assertEquals(3, fail.nodeRuns().get(0).attempts());
    }

    @Test
    void 检查点留档与非法图拒绝() {
        WorkflowGraph g = WorkflowGraph.builder("cp")
                .node("a", "TASK", Map.of())
                .node("b", "TASK", Map.of())
                .edge("a", "b").build();
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        GraphExecutor executor = new GraphExecutor(store, null, NO_SLEEP);
        GraphExecutor.ExecutionReport report = executor.execute(g, Map.of(), (node, ctx) -> node.id());
        // 每节点成功后各留一快照（最新快照 pending=b、已完成含 a）
        var latest = store.findLatest(report.runId());
        assertNotNull(latest);
        assertEquals("b", latest.pendingNodeId());
        assertTrue(latest.completedNodeIds().contains("a"));
        // 非法图（环）直接拒绝
        WorkflowGraph cyclic = WorkflowGraph.builder("cyc")
                .node("a", "TASK", Map.of())
                .node("b", "TASK", Map.of())
                .edge("a", "b").edge("b", "a").build();
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(cyclic, Map.of(), (node, ctx) -> node.id()));
    }

    @Test
    void 中断挂起与恢复续跑() {
        WorkflowGraph g = WorkflowGraph.builder("hitl")
                .node("a", "TASK", Map.of())
                .node("gate", WorkflowGraph.TYPE_INTERRUPT, Map.of())
                .node("b", "TASK", Map.of())
                .edge("a", "gate").edge("gate", "b").build();
        InterruptRegistry registry = new InterruptRegistry();
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        GraphExecutor executor = new GraphExecutor(store, registry, NO_SLEEP);
        List<String> visited = new ArrayList<>();
        GraphExecutor.ExecutionReport interrupted = executor.execute(g, Map.of(), (node, ctx) -> {
            visited.add(node.id());
            return node.id();
        });
        // a 执行，gate 挂起，b 未执行
        assertEquals(GraphExecutor.STATUS_INTERRUPTED, interrupted.status());
        assertEquals(List.of("a"), visited);
        assertEquals(1, registry.size());
        assertNotNull(interrupted.interruptToken());

        // 错误 token 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> executor.resume(registry, "bad-token", "ok", (node, ctx) -> node.id()));

        // resume：注入人工输入，b 续跑
        GraphExecutor.ExecutionReport resumed = executor.resume(registry, interrupted.interruptToken(),
                "approved", (node, ctx) -> {
                    visited.add(node.id());
                    return "gate".equals(node.id()) ? ctx.getString("gate.human_input") : node.id();
                });
        assertEquals(GraphExecutor.STATUS_COMPLETED, resumed.status());
        assertTrue(visited.contains("b"), "b 应已续跑执行");
        assertEquals(0, registry.size());
        // 消费后 token 不可复用
        assertThrows(IllegalArgumentException.class,
                () -> executor.resume(registry, interrupted.interruptToken(), "again",
                        (node, ctx) -> node.id()));
    }

    @Test
    void 检查点续跑已完成节点不重执行() {
        WorkflowGraph g = WorkflowGraph.builder("ckpt-resume")
                .node("a", "TASK", Map.of())
                .node("b", "TASK", Map.of())
                .edge("a", "b").build();
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        GraphExecutor executor = new GraphExecutor(store, new InterruptRegistry(), NO_SLEEP);
        AtomicInteger aCalls = new AtomicInteger();
        executor.execute(g, Map.of(), (node, ctx) -> {
            if (node.id().equals("a")) {
                aCalls.incrementAndGet();
            }
            return node.id();
        });
        // 模拟检查点续跑：注册挂起于 a（completed 为空但 a 已在本次手动标记完成）
        InterruptRegistry registry = new InterruptRegistry();
        String token = registry.register("run-x", "a", g, java.util.Set.of("a"), Map.of("k", "v"));
        GraphExecutor executor2 = new GraphExecutor(store, registry, NO_SLEEP);
        GraphExecutor.ExecutionReport report = executor2.resume(registry, token, "in", (node, ctx) -> {
            if (node.id().equals("a")) {
                aCalls.incrementAndGet();
            }
            return node.id();
        });
        assertEquals(GraphExecutor.STATUS_COMPLETED, report.status());
        assertEquals(1, aCalls.get(), "resume 中 a 已完成不应重跑");
        // b 执行且可见续跑上下文
        assertTrue(report.nodeRuns().stream().anyMatch(n -> n.nodeId().equals("b")));
    }
}
