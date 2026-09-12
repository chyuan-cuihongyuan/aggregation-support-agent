package cn.chyuan.ai.domain.crew.service;

import cn.chyuan.ai.domain.crew.model.AgentRole;
import cn.chyuan.ai.domain.crew.model.HandoffMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 多智能体流程执行器（工单 0214 AC2 顺序流程 / 0215 AC3 层级流程；复用 domain 纯内核）—
 * SEQUENTIAL：任务链按序交接，前序输出注入后续上下文（CrewAI sequential process）；
 * HIERARCHICAL：manager 按分派策略（默认轮转）把任务派给 worker 执行并汇总结论
 * （CrewAI hierarchical / MetaGPT SOP 思想）。每步经 {@link CrewBudget} 记账，超限截断
 * （BUDGET_EXCEEDED 事件 + 部分结果）；全程 {@link CrewRunRecorder} 录制。
 * LLM 经 {@link LlmPort} 函数式注入（测试用桩，生产接既有模型端口）。
 *
 * @author chyuan
 */
public class CrewProcess {

    /** LLM 调用端口（提示词 → 回复；函数式注入） */
    @FunctionalInterface
    public interface LlmPort {
        String invoke(String prompt) throws Exception;
    }

    /** 分派策略：任务序号 + worker 角色 → 选中角色（默认轮转） */
    public interface DispatchStrategy extends BiFunction<CrewTask, List<String>, String> {
    }

    /** 团队任务 */
    public record CrewTask(String description, String assignedRole) {
        public CrewTask {
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("任务描述不能为空");
            }
        }

        public static CrewTask of(String description) {
            return new CrewTask(description, null);
        }
    }

    /** 执行结果：COMPLETED / BUDGET_EXCEEDED / FAILED */
    public record CrewResult(String status, String finalOutput, List<HandoffMessage> handoffs,
            List<String> skippedTasks, String stopReason) {
    }

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    /** token 粗估口径：CJK 按字符近似（预算语义为"步内字符量级"，非精确计费） */
    static long estimateTokens(String text) {
        return text == null ? 0 : text.length();
    }

    /** 顺序流程执行 */
    public CrewResult runSequential(AgentRoleRegistry registry, List<AgentRole> roles,
            List<CrewTask> tasks, LlmPort llmPort, CrewBudget budget,
            CrewRunRecorder recorder, Blackboard blackboard) {
        List<HandoffMessage> handoffs = new ArrayList<>();
        String previousOutput = null;
        String previousRole = null;
        for (int i = 0; i < tasks.size(); i++) {
            CrewTask task = tasks.get(i);
            // 角色选择：任务指定 > 轮转
            AgentRole role = task.assignedRole() != null
                    ? registry.get(task.assignedRole())
                    : roles.get(i % roles.size());
            if (role == null) {
                return new CrewResult(STATUS_FAILED, previousOutput, handoffs, List.of(),
                        "任务角色未注册: " + task.assignedRole());
            }
            // 预算记账（AC6）：步数 + token 粗估
            String prompt = role.personaPrompt() + "\n任务：" + task.description()
                    + (previousOutput == null ? "" : "\n上一步结论：" + previousOutput);
            if (!budget.consumeStep(estimateTokens(prompt))) {
                recorder.record(role.role(), CrewBudget.EVENT_BUDGET_EXCEEDED, budget.exceededReason());
                return new CrewResult(STATUS_BUDGET_EXCEEDED, previousOutput, handoffs, List.of(),
                        budget.exceededReason());
            }
            String output;
            try {
                output = llmPort.invoke(prompt);
            } catch (Exception e) {
                recorder.record(role.role(), "TASK_FAILED", e.getMessage());
                return new CrewResult(STATUS_FAILED, previousOutput, handoffs, List.of(),
                        "任务执行失败: " + e.getMessage());
            }
            // 交接协议（AC4）：前序角色 → 本角色
            handoffs.add(new HandoffMessage(previousRole == null ? role.role() : previousRole,
                    role.role(), task.description(), output, List.of()));
            blackboard.put("task." + (i + 1) + ".output", output);
            recorder.record(role.role(), "EXECUTE_TASK", task.description() + " → " + abbreviate(output));
            previousOutput = output;
            previousRole = role.role();
        }
        recorder.record(previousRole == null ? "crew" : previousRole, "COMPLETE", "顺序流程完成");
        return new CrewResult(STATUS_COMPLETED, previousOutput, handoffs, List.of(), null);
    }

    /**
     * 层级流程执行：manager 逐任务按分派策略选 worker 执行（worker 失败跳过并标记），
     * 全部完成后 manager 汇总各 worker 结论为最终输出。
     */
    public CrewResult runHierarchical(AgentRoleRegistry registry, AgentRole managerRole,
            List<AgentRole> workers, List<CrewTask> tasks, LlmPort llmPort, CrewBudget budget,
            CrewRunRecorder recorder, Blackboard blackboard, DispatchStrategy dispatchStrategy) {
        DispatchStrategy dispatch = dispatchStrategy != null ? dispatchStrategy
                : (task, candidates) -> candidates.get(0);
        List<HandoffMessage> handoffs = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            CrewTask task = tasks.get(i);
            List<String> candidateNames = workers.stream().map(AgentRole::role).toList();
            if (candidateNames.isEmpty()) {
                return new CrewResult(STATUS_FAILED, null, handoffs, skipped, "无可用 worker");
            }
            String workerName = dispatch.apply(task, candidateNames);
            AgentRole worker = registry.get(workerName);
            if (worker == null) {
                return new CrewResult(STATUS_FAILED, null, handoffs, skipped, "worker 未注册: " + workerName);
            }
            String prompt = worker.personaPrompt() + "\n（manager " + managerRole.role()
                    + " 分派）任务：" + task.description();
            if (!budget.consumeStep(estimateTokens(prompt))) {
                recorder.record(managerRole.role(), CrewBudget.EVENT_BUDGET_EXCEEDED, budget.exceededReason());
                return new CrewResult(STATUS_BUDGET_EXCEEDED, String.join("\n", summaries), handoffs,
                        skipped, budget.exceededReason());
            }
            String output;
            try {
                output = llmPort.invoke(prompt);
            } catch (Exception e) {
                // worker 失败降级：跳过并标记，不中断整体
                skipped.add(task.description());
                recorder.record(worker.role(), "WORKER_SKIPPED", task.description() + "：" + e.getMessage());
                continue;
            }
            summaries.add(worker.role() + "：" + output);
            handoffs.add(new HandoffMessage(managerRole.role(), worker.role(), task.description(),
                    output, List.of()));
            blackboard.put("task." + (i + 1) + ".output", output);
            recorder.record(worker.role(), "EXECUTE_TASK", task.description() + " → " + abbreviate(output));
        }
        // manager 汇总
        String summaryPrompt = managerRole.personaPrompt() + "\n汇总以下 worker 结论：\n"
                + String.join("\n", summaries);
        if (!budget.consumeStep(estimateTokens(summaryPrompt))) {
            recorder.record(managerRole.role(), CrewBudget.EVENT_BUDGET_EXCEEDED, budget.exceededReason());
            return new CrewResult(STATUS_BUDGET_EXCEEDED, String.join("\n", summaries), handoffs,
                    skipped, budget.exceededReason());
        }
        String finalOutput;
        try {
            finalOutput = llmPort.invoke(summaryPrompt);
        } catch (Exception e) {
            recorder.record(managerRole.role(), "SUMMARY_FAILED", e.getMessage());
            return new CrewResult(STATUS_FAILED, String.join("\n", summaries), handoffs, skipped,
                    "汇总失败: " + e.getMessage());
        }
        recorder.record(managerRole.role(), "SUMMARIZE", abbreviate(finalOutput));
        return new CrewResult(STATUS_COMPLETED, finalOutput, handoffs, skipped, null);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }
}
