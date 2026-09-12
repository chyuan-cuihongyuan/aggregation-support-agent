package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 图执行引擎（工单 0205 AB2；集成 AB3 检查点 / AB4 人工中断 / AB6 重试）—
 * 就绪队列按拓扑序执行：入度 0 节点先跑，成功后递减后继入度、就绪即入队；
 * 节点输出写 ctx（键 = {nodeId}.output）；失败短路（下游不执行）。
 * 每节点成功后落检查点快照；INTERRUPT 节点挂起登记 token，等 resume 注入人工输入续跑；
 * 可重试异常按节点 RetryPolicy 指数退避重试，耗尽即失败。
 * domain 纯内核：零框架依赖，NodeExecutor 函数式注入。
 *
 * @author chyuan
 */
public class GraphExecutor {

    /** 节点执行器（业务语义由调用方注入；测试用 lambda 桩） */
    @FunctionalInterface
    public interface NodeExecutor {
        String execute(WorkflowGraph.NodeSpec node, WorkflowContext ctx) throws Exception;
    }

    /** 单节点运行记录 */
    public record NodeRun(String nodeId, String status, int attempts, long durationMs, String error) {
        public static final String SUCCESS = "SUCCESS";
        public static final String FAILED = "FAILED";
        public static final String INTERRUPTED = "INTERRUPTED";
    }

    /** 执行报告：COMPLETED / FAILED / INTERRUPTED */
    public record ExecutionReport(String runId, String status, List<NodeRun> nodeRuns,
            String failedNodeId, String error, String interruptToken) {
    }

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";

    /** 检查点存储（可 null=不留检查点） */
    private final cn.chyuan.ai.domain.workflow.adapter.port.ICheckpointStore checkpointStore;
    /** 中断注册表（可 null=INTERRUPT 节点按普通节点执行语义报错） */
    private final InterruptRegistry interruptRegistry;
    /** 重试睡眠（可注入免真实等待） */
    private final Sleeper sleeper;

    /** 睡眠函数抽象（测试注入 no-op） */
    public interface Sleeper {
        void sleep(long ms);
    }

    public GraphExecutor() {
        this(null, null, ms -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public GraphExecutor(cn.chyuan.ai.domain.workflow.adapter.port.ICheckpointStore checkpointStore,
            InterruptRegistry interruptRegistry, Sleeper sleeper) {
        this.checkpointStore = checkpointStore;
        this.interruptRegistry = interruptRegistry;
        this.sleeper = sleeper == null ? ms -> {
        } : sleeper;
    }

    /** 全量执行：从入度 0 节点开始；非法图抛 IllegalArgumentException */
    public ExecutionReport execute(WorkflowGraph graph, Map<String, Object> input, NodeExecutor executor) {
        GraphValidator.ValidationResult validation = GraphValidator.validate(graph);
        if (!validation.valid()) {
            throw new IllegalArgumentException("图非法: " + String.join("; ", validation.errors()));
        }
        String runId = UUID.randomUUID().toString();
        WorkflowContext ctx = new WorkflowContext();
        if (input != null) {
            input.forEach(ctx::put);
        }
        return drive(graph, runId, ctx, new LinkedHashSet<>(), executor, new AtomicLong());
    }

    /** 人工中断恢复（工单 0207 AB4）：消费 token 注入人工输入，从断点续跑 */
    public ExecutionReport resume(InterruptRegistry registry, String token, String userInput,
            NodeExecutor executor) {
        InterruptRegistry.PendingInterrupt pending = registry.consume(token);
        if (pending == null) {
            throw new IllegalArgumentException("中断令牌无效或已被消费");
        }
        WorkflowContext ctx = WorkflowContext.fromSnapshot(pending.contextSnapshot());
        ctx.put(pending.nodeId() + ".human_input", userInput);
        Set<String> completed = new LinkedHashSet<>(pending.completedNodeIds());
        completed.add(pending.nodeId());
        return drive(pending.graph(), pending.runId(), ctx, completed, executor,
                new AtomicLong(System.nanoTime()));
    }

    /** 核心驱动循环（执行/续跑共用）：就绪队列由剩余入度表自举（已完成节点计为已消化） */
    private ExecutionReport drive(WorkflowGraph graph, String runId, WorkflowContext ctx,
            Set<String> completed, NodeExecutor executor, AtomicLong checkpointSeq) {
        List<NodeRun> nodeRuns = new ArrayList<>();
        Map<String, List<String>> adjacency = GraphValidator.adjacencyOf(graph);
        Map<String, WorkflowGraph.NodeSpec> nodeById = nodeIndex(graph);
        Map<String, Integer> remainingIndegree = GraphValidator.indegreeOf(graph);
        // 已完成节点视为已消化：自身移除 + 后继入度递减
        for (String doneId : completed) {
            remainingIndegree.remove(doneId);
            for (String next : adjacency.getOrDefault(doneId, List.of())) {
                remainingIndegree.merge(next, -1, Integer::sum);
            }
        }
        Queue<String> ready = new LinkedList<>();
        for (Map.Entry<String, Integer> e : remainingIndegree.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }

        while (!ready.isEmpty()) {
            String nodeId = ready.poll();
            if (completed.contains(nodeId)) {
                continue;
            }
            WorkflowGraph.NodeSpec node = nodeById.get(nodeId);

            // 人工中断节点（AB4）：挂起登记，等待 resume
            if (WorkflowGraph.TYPE_INTERRUPT.equals(node.type())) {
                nodeRuns.add(new NodeRun(nodeId, NodeRun.INTERRUPTED, 1, 0, null));
                String token = interruptRegistry == null ? null
                        : interruptRegistry.register(runId, nodeId, graph, completed, ctx.snapshot());
                saveCheckpoint(runId, checkpointSeq, graph, nodeId, completed, ctx);
                return new ExecutionReport(runId, STATUS_INTERRUPTED, List.copyOf(nodeRuns), nodeId,
                        "等待人工输入: " + nodeId, token);
            }

            // 业务节点执行（AB6 重试：可重试异常指数退避，耗尽失败）
            RetryPolicy policy = RetryPolicy.fromConfig(node.config());
            long start = System.currentTimeMillis();
            int attempts = 0;
            String output = null;
            String error = null;
            while (attempts < policy.maxAttempts()) {
                attempts++;
                try {
                    output = executor.execute(node, ctx);
                    error = null;
                    break;
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    if (attempts < policy.maxAttempts()) {
                        sleeper.sleep(RetryPolicy.BackoffCalculator.delayWithJitterMs(policy, attempts,
                                () -> 0.5d));
                    }
                }
            }
            long duration = System.currentTimeMillis() - start;
            if (error != null) {
                nodeRuns.add(new NodeRun(nodeId, NodeRun.FAILED, attempts, duration, error));
                saveCheckpoint(runId, checkpointSeq, graph, nodeId, completed, ctx);
                return new ExecutionReport(runId, STATUS_FAILED, List.copyOf(nodeRuns), nodeId, error, null);
            }
            completed.add(nodeId);
            ctx.put(nodeId + ".output", output);
            nodeRuns.add(new NodeRun(nodeId, NodeRun.SUCCESS, attempts, duration, null));
            saveCheckpoint(runId, checkpointSeq, graph, nodeId, completed, ctx);
            // 后继入度递减，就绪入队
            for (String next : adjacency.getOrDefault(nodeId, List.of())) {
                if (!completed.contains(next) && remainingIndegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        return new ExecutionReport(runId, STATUS_COMPLETED, List.copyOf(nodeRuns), null, null, null);
    }

    private void saveCheckpoint(String runId, AtomicLong seq, WorkflowGraph graph, String pendingNodeId,
            Set<String> completed, WorkflowContext ctx) {
        if (checkpointStore == null) {
            return;
        }
        checkpointStore.save(new Checkpoint(runId, seq.incrementAndGet(), graph, pendingNodeId,
                Set.copyOf(completed), ctx.snapshot(), System.currentTimeMillis()));
    }

    private static Map<String, WorkflowGraph.NodeSpec> nodeIndex(WorkflowGraph graph) {
        Map<String, WorkflowGraph.NodeSpec> index = new HashMap<>();
        for (WorkflowGraph.NodeSpec node : graph.nodes()) {
            index.put(node.id(), node);
        }
        return index;
    }
}
