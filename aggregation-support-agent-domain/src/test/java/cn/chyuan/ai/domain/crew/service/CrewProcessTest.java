package cn.chyuan.ai.domain.crew.service;

import cn.chyuan.ai.domain.crew.model.AgentRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程执行单测（工单 0214/0215/0218 AC2/AC3/AC6）：
 * 顺序交接/层级分派汇总/预算截断部分结果。
 */
class CrewProcessTest {

    private static AgentRole role(String name) {
        return new AgentRole(name, "目标-" + name, null, null);
    }

    @Test
    void 顺序流程交接与上下文注入() {
        AgentRoleRegistry registry = new AgentRoleRegistry();
        registry.register(role("研究员"));
        registry.register(role("写手"));
        CrewProcess process = new CrewProcess();
        CrewRunRecorder recorder = new CrewRunRecorder("run-seq");
        AtomicInteger calls = new AtomicInteger();
        CrewProcess.CrewResult result = process.runSequential(
                registry, List.of(role("研究员"), role("写手")),
                List.of(CrewProcess.CrewTask.of("查资料"), CrewProcess.CrewTask.of("写报告")),
                prompt -> {
                    calls.incrementAndGet();
                    if (prompt.contains("查资料")) {
                        return "资料OK";
                    }
                    // 第二步应注入第一步结论
                    assertTrue(prompt.contains("上一步结论：资料OK"));
                    return "报告OK";
                },
                new CrewBudget(10, 10_000), recorder, new Blackboard());
        assertEquals(CrewProcess.STATUS_COMPLETED, result.status());
        assertEquals("报告OK", result.finalOutput());
        assertEquals(2, calls.get());
        assertEquals(2, result.handoffs().size());
        // 交接链：写手 → 研究员 → 写手（首步 from=to 自身）
        assertEquals("研究员", result.handoffs().get(0).toRole());
        assertEquals("研究员", result.handoffs().get(1).fromRole());
        assertEquals("写手", result.handoffs().get(1).toRole());
        // 黑板沉淀
        assertEquals("资料OK", result.handoffs().get(0).summary());
        assertEquals(3, recorder.size()); // 2 步执行 + 1 COMPLETE
    }

    @Test
    void 层级流程分派汇总与失败降级() {
        AgentRoleRegistry registry = new AgentRoleRegistry();
        registry.register(role("manager"));
        registry.register(role("w1"));
        registry.register(role("w2"));
        CrewProcess process = new CrewProcess();
        CrewRunRecorder recorder = new CrewRunRecorder("run-hier");
        // 轮转分派（显式策略）：任务 0→w1，任务 1→w2
        CrewProcess.CrewResult result = process.runHierarchical(
                registry, role("manager"), List.of(role("w1"), role("w2")),
                List.of(CrewProcess.CrewTask.of("任务A"), CrewProcess.CrewTask.of("任务B"),
                        CrewProcess.CrewTask.of("任务C")),
                prompt -> {
                    if (prompt.contains("任务B")) {
                        throw new IllegalStateException("worker 挂了");
                    }
                    return prompt.contains("汇总") ? "总-OK" : "分-OK";
                },
                new CrewBudget(20, 100_000), recorder, new Blackboard(),
                (task, candidates) -> candidates.get(0));
        // 汇总成功但任务 B 被跳过
        assertEquals(CrewProcess.STATUS_COMPLETED, result.status());
        assertNotNull(result.skippedTasks());
        assertEquals(1, result.skippedTasks().size());
        // w2 从未被分派（轮转策略恒选第一个）
        assertTrue(recorder.steps().stream().noneMatch(s -> s.role().equals("w2")));
    }

    @Test
    void 预算超限截断与部分结果() {
        AgentRoleRegistry registry = new AgentRoleRegistry();
        registry.register(role("r"));
        CrewProcess process = new CrewProcess();
        CrewRunRecorder recorder = new CrewRunRecorder("run-budget");
        // 步数上限 2：三任务在第三步超限 → BUDGET_EXCEEDED + 部分结果
        CrewBudget budget = new CrewBudget(2, 1_000_000);
        CrewProcess.CrewResult result = process.runSequential(
                registry, List.of(role("r")),
                List.of(CrewProcess.CrewTask.of("甲"), CrewProcess.CrewTask.of("乙"),
                        CrewProcess.CrewTask.of("丙")),
                prompt -> "out", budget, recorder, new Blackboard());
        assertEquals(CrewProcess.STATUS_BUDGET_EXCEEDED, result.status());
        assertTrue(result.stopReason() != null && result.stopReason().contains("步数超限"));
        assertEquals("out", result.finalOutput()); // 已完成两步的部分结果
        assertTrue(budget.isExhausted());
        assertTrue(recorder.steps().stream().anyMatch(s -> s.action().equals("BUDGET_EXCEEDED")));
    }

    @Test
    void token预算超限() {
        AgentRoleRegistry registry = new AgentRoleRegistry();
        registry.register(role("r"));
        CrewProcess process = new CrewProcess();
        // token 上限极小：第一步即超限
        CrewBudget budget = new CrewBudget(10, 1);
        CrewProcess.CrewResult result = process.runSequential(
                registry, List.of(role("r")), List.of(CrewProcess.CrewTask.of("这是一个会超预算的长任务")),
                prompt -> "out", budget, new CrewRunRecorder("t"), new Blackboard());
        assertEquals(CrewProcess.STATUS_BUDGET_EXCEEDED, result.status());
        assertTrue(budget.exceededReason().contains("token"));
    }
}
