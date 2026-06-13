package cn.chyuan.ai.domain.agent.service.armory.node.workflow;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LoopAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Reflexion 反思迭代工作流节点 — M2: 真正的 Actor → Critic → Reflector + 跨迭代记忆
 *
 * <h3>工作流拓扑（真正的跨迭代记忆，非空壳）</h3>
 * <pre>
 * LoopAgent(rag_reflexion_loop, maxIterations=3)
 * ├─ Actor (ragAssistant)
 * │    beforeModelCallback: 读取 state["reflections:{name}"]，把累积反思注入 instruction
 * │    outputKey: rag_answer
 * ├─ Critic (rag_critic)
 * │    tools: [ExitLoopTool] + afterModelCallback 强门控（PASSED→escalate 退出循环）
 * │    outputKey: rag_evaluation
 * └─ Reflector (rag_reflector)
 *      afterAgentCallback: 读取 outputKey["rag_reflection"]，追加到 state["reflections:{name}"]
 *      outputKey: rag_reflection
 * </pre>
 *
 * <h3>跨迭代记忆机制</h3>
 * <ul>
 *   <li>Reflector 每轮产出反思后，afterAgentCallback 把反思追加到 session state 的累积列表</li>
 *   <li>Actor 下一轮执行前，beforeModelCallback 读取累积列表，拼接到 instruction（"历史反思教训"）</li>
 *   <li>Session state 在 LoopAgent 生命周期内共享（ConcurrentMap），无需外部存储</li>
 * </ul>
 *
 * <h3>子 agent 约定顺序</h3>
 * subAgents[0]=Actor, [1]=Critic, [2]=Reflector。
 * 子 agent 不足 3 个时降级为纯顺序执行，保持向后兼容。
 *
 * @author chyuan
 * @since 2026-06-13
 */
@Slf4j
@Service("reflexionAgentNode")
public class ReflexionAgentNode extends AbstractArmorySupport {

    /** Reflexion 工作流要求的最少子 agent 数：Actor、Critic、Reflector */
    private static final int MIN_SUB_AGENTS = 3;

    /** Critic 输出中表示"评估通过"的关键字（命中即触发退出） */
    private static final String PASS_KEYWORD = "PASSED";

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("【M2】Ai Agent 装配操作 - ReflexionAgentNode（真正的跨迭代记忆）");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();
        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();

        if (subAgentNames == null || subAgentNames.size() < MIN_SUB_AGENTS) {
            log.warn("Reflexion 工作流需要 {} 个子 agent（Actor、Critic、Reflector），当前: {}，降级为顺序执行",
                    MIN_SUB_AGENTS, subAgentNames == null ? 0 : subAgentNames.size());
            return buildFallbackSequential(requestParameter, currentAgentWorkflow, dynamicContext);
        }

        // 约定顺序：[0]Actor [1]Critic [2]Reflector
        String actorName = subAgentNames.get(0);
        String criticName = subAgentNames.get(1);
        String reflectorName = subAgentNames.get(2);

        // #5 先验证子 agent 完整性（在 enhance 之前）—— 避免 enhance 后才发现缺失，
        // 导致降级 SequentialAgent 含被增强的子 agent（挂 ExitLoopTool/记忆 callback）
        BaseAgent actor = dynamicContext.getAgentGroup().get(actorName);
        BaseAgent critic = dynamicContext.getAgentGroup().get(criticName);
        BaseAgent reflector = dynamicContext.getAgentGroup().get(reflectorName);
        if (actor == null || critic == null || reflector == null) {
            log.error("Reflexion 工作流子 agent 缺失，降级为顺序执行（enhance 前）: actor={}, critic={}, reflector={}",
                    actor, critic, reflector);
            return buildFallbackSequential(requestParameter, currentAgentWorkflow, dynamicContext);
        }

        // 从配置读取 Reflector 的 outputKey（用于记忆写入源）
        String reflectorOutputKey = resolveOutputKey(requestParameter, reflectorName, "rag_reflection");

        // 跨迭代反思记忆的 state key（默认 "reflections:{workflowName}"）
        String stateKey = (currentAgentWorkflow.getReflectionStateKey() != null && !currentAgentWorkflow.getReflectionStateKey().isBlank())
                ? currentAgentWorkflow.getReflectionStateKey()
                : "reflections:" + currentAgentWorkflow.getName();

        int maxIterations = currentAgentWorkflow.getMaxIterations() != null ? currentAgentWorkflow.getMaxIterations() : 3;

        // 确认完整后再增强三种子 agent
        boolean criticEnhanced = dynamicContext.enhanceAgent(criticName,
                ctx -> AgenticWorkflowEnhancer.attachExitLoopGate(ctx, PASS_KEYWORD));
        if (!criticEnhanced) {
            log.warn("Critic[{}] 无 Builder 缓存，无法挂 ExitLoopTool，退化为普通顺序节点", criticName);
        }
        boolean reflectorEnhanced = dynamicContext.enhanceAgent(reflectorName,
                ctx -> AgenticWorkflowEnhancer.attachReflectionWriter(ctx, reflectorOutputKey, stateKey));
        boolean actorEnhanced = dynamicContext.enhanceAgent(actorName,
                ctx -> AgenticWorkflowEnhancer.attachReflectionReader(ctx, stateKey));
        // enhance 覆盖后重新取实例
        actor = dynamicContext.getAgentGroup().get(actorName);
        critic = dynamicContext.getAgentGroup().get(criticName);
        reflector = dynamicContext.getAgentGroup().get(reflectorName);

        // 构建 Reflexion Loop：Actor → Critic → Reflector
        // 每轮 Actor 执行（带累积反思）→ Critic 评估（PASSED 退出）→ Reflector 产出反思（累积到 state）
        LoopAgent reflexionLoop = LoopAgent.builder()
                .name(currentAgentWorkflow.getName())
                .description(currentAgentWorkflow.getDescription())
                .subAgents(actor, critic, reflector)
                .maxIterations(maxIterations)
                .build();

        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), reflexionLoop);

        log.info("Reflexion 反思迭代工作流装配完成: name={}, actor={}, critic={}, reflector={}, stateKey={}, maxIterations={}, " +
                        "增强状态: actor={}, critic={}, reflector={}",
                currentAgentWorkflow.getName(), actorName, criticName, reflectorName, stateKey, maxIterations,
                actorEnhanced, criticEnhanced, reflectorEnhanced);

        return router(requestParameter, dynamicContext);
    }

    /** 从配置读取指定 agent 的 outputKey，找不到时返回默认值 */
    private String resolveOutputKey(ArmoryCommandEntity requestParameter, String agentName, String defaultOutputKey) {
        try {
            List<AiAgentConfigTableVO.Module.Agent> agents = requestParameter.getAiAgentConfigTableVO().getModule().getAgents();
            if (agents != null) {
                return agents.stream()
                        .filter(a -> agentName.equals(a.getName()))
                        .map(AiAgentConfigTableVO.Module.Agent::getOutputKey)
                        .findFirst()
                        .orElse(defaultOutputKey);
            }
        } catch (Exception e) {
            log.warn("读取 agent[{}] outputKey 失败，使用默认值: {}", agentName, defaultOutputKey);
        }
        return defaultOutputKey;
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(
            ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }

}
