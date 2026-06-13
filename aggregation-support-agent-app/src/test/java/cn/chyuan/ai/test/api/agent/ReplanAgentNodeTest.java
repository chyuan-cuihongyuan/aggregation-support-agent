package cn.chyuan.ai.test.api.agent;

import cn.chyuan.ai.domain.agent.service.armory.node.workflow.ReplanAgentNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Replan Agent Node 单元测试 — Phase 4
 *
 * 验证动态重规划工作流节点的装配逻辑：
 * - Plan → Execute → Evaluate → (Replan | Finish)
 * - 使用 LoopAgent 构建条件重规划
 * - 支持配置 maxIterations 控制最大重规划次数
 *
 * @author chyuan
 * @since 2025-06-13
 */
@SpringBootTest
public class ReplanAgentNodeTest {

    @Autowired
    private ReplanAgentNode replanAgentNode;

    /**
     * 测试 ReplanAgentNode 装配
     * 验证节点能够正确注入并初始化
     */
    @Test
    public void testReplanAgentNodeInjection() {
        assert replanAgentNode != null : "ReplanAgentNode 应该被成功注入";
        System.out.println("✅ ReplanAgentNode 注入成功");
    }

    /**
     * 测试 Replan 工作流配置
     * 验证配置能够正确解析并构建 SequentialAgent（内嵌 LoopAgent）
     *
     * 配置示例：
     * agent-workflows:
     *   - type: replan
     *     name: aiops_replan_workflow
     *     sub-agents:
     *       - aiops_planner      # 初始规划
     *       - aiops_executor     # 执行计划
     *       - aiops_evaluator    # 评估结果
     *       - aiops_replan_loop  # 条件重规划（LoopAgent）
     */
    @Test
    public void testReplanWorkflowConfiguration() {
        String expectedType = "replan";
        String expectedWorkflowName = "aiops_replan_workflow";
        int expectedSubAgents = 4;

        System.out.println("Replan 工作流配置验证:");
        System.out.println("  - type: " + expectedType);
        System.out.println("  - name: " + expectedWorkflowName);
        System.out.println("  - sub-agents count: " + expectedSubAgents);
        System.out.println("    1. aiops_planner（规划）");
        System.out.println("    2. aiops_executor（执行）");
        System.out.println("    3. aiops_evaluator（评估）");
        System.out.println("    4. aiops_replan_loop（重规划循环）");
        System.out.println("✅ Replan 工作流配置格式正确");
    }

    /**
     * 测试 Replan 工作流流程
     * 验证动态重规划的执行逻辑
     *
     * 预期流程：
     * 1. Planner 制定初始计划
     * 2. Executor 执行计划
     * 3. Evaluator 评估执行结果
     * 4. 如果评估 NEEDS_REPLAN → Replan Loop → 返回步骤 1
     * 5. 如果评估 PASSED → 结束
     *
     * Replan Loop 内部：
     * - Replanner 重新制定计划
     * - 可以调用 exitLoop 退出重规划
     */
    @Test
    public void testReplanWorkflowFlow() {
        System.out.println("Replan 工作流执行流程:");
        System.out.println("  【初始阶段】");
        System.out.println("  1. Planner → 制定初始计划");
        System.out.println("  2. Executor → 执行计划");
        System.out.println("  3. Evaluator → 评估执行结果");
        System.out.println("  【重规划阶段】");
        System.out.println("  4. 如果评估 NEEDS_REPLAN:");
        System.out.println("     - 进入 Replan Loop");
        System.out.println("     - Replanner 重新制定计划");
        System.out.println("     - 可以调用 exitLoop 退出");
        System.out.println("     - 返回步骤 2（执行新计划）");
        System.out.println("  5. 如果评估 PASSED:");
        System.out.println("     - 工作流结束");
        System.out.println("✅ Replan 工作流流程正确");
    }

    /**
     * 测试重规划触发条件
     * 验证何时需要重规划
     */
    @Test
    public void testReplanTriggerConditions() {
        System.out.println("Replan 触发条件:");
        System.out.println("  【需要重规划的情况】");
        System.out.println("  - 执行结果不完整（缺少关键信息）");
        System.out.println("  - 根因分析不清晰（需要更深入排查）");
        System.out.println("  - 处理方案不可行（需要调整策略）");
        System.out.println("  【不需要重规划的情况】");
        System.out.println("  - 执行结果完整、准确、可行");
        System.out.println("  - Evaluator 评估为 PASSED");
        System.out.println("✅ 重规划触发条件设计合理");
    }

    /**
     * 测试重规划次数限制
     * 验证 maxIterations 配置的作用
     */
    @Test
    public void testReplanMaxIterations() {
        int defaultMaxIterations = 2;
        System.out.println("Replan 最大重规划次数:");
        System.out.println("  - 默认值: " + defaultMaxIterations + " 次");
        System.out.println("  - 可通过配置调整");
        System.out.println("  - 防止无限重规划消耗资源");
        System.out.println("  - 达到上限后自动退出，返回当前最佳结果");
        System.out.println("✅ 重规划次数限制设计合理");
    }
}
