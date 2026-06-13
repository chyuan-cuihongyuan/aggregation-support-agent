package cn.chyuan.ai.domain.agent.service.armory.node;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.chyuan.ai.domain.agent.service.armory.matter.patch.MySpringAI;
import cn.chyuan.ai.domain.agent.service.armory.matter.tools.ExitLoopTool;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.FunctionTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {

    private static final String REACT_PROMPT_PREFIX = """
            You are working with tool-calling capability in ReAct (Reasoning + Acting) mode.

            ## Core Principle: Use tools proactively when they can provide accurate information
            You have access to specialized tools. Use them when they can provide more accurate,
            up-to-date, or specific information than your general knowledge.

            ## When to use tools:
            - Any question about specific data, facts, or information that exists in the knowledge base
            - Questions about prices, specifications, policies, or any domain-specific knowledge
            - The user asks about anything that could be in the internal documents
            - When accuracy matters more than speed

            ## When NOT to use tools:
            - Pure greetings, chitchat, or casual conversation
            - Creative writing, opinions, or subjective reasoning
            - Simple math or logic puzzles unrelated to document knowledge

            ## ReAct Loop:
            Thought: Analyze the user's request and determine which tool to use
            Action: Call the appropriate tool
            Observation: (The system will return the tool execution result)

            Trust your tool results — they are already optimized and reranked.
            Do not re-query for items you have already retrieved.

            ## Rules:
            - When in doubt about whether to use a tool, prefer using it
            - Always use tools for factual queries about data in the knowledge base
            - Do not substitute your own knowledge for tool results on factual questions
            - NEVER re-query the same or similar items after a successful tool call.
              If batch query results are incomplete for some items, note it in your response instead of re-querying.
            - Each user question should trigger at most ONE round of tool calling (one batch or one single).
              Do NOT make follow-up tool calls for the same items in the same conversation turn.
            - Your final response should contain only the substantive content, do not include labels like "Final Answer:" or "Thought:"
            """;

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        ChatModel defaultChatModel = dynamicContext.getChatModel();
        ChatModel noToolChatModel = dynamicContext.getNoToolChatModel();
        boolean globalHasTools = dynamicContext.isHasTools();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            String instruction = agentConfig.getInstruction();

            // 【新增】按 agent.tools 声明选择 ChatModel：
            // - tools == null：使用主 ChatModel（默认行为，带全部工具）
            // - tools == []（空列表）：使用无工具变体（Planner/Critic 等纯推理 agent）
            // - tools == 非空白名单：本期暂不支持，降级为主 ChatModel（后续扩展按名过滤）
            ChatModel effectiveChatModel;
            boolean effectiveHasTools;
            if (agentConfig.getTools() != null && agentConfig.getTools().isEmpty()) {
                // 显式空列表 → 无工具变体
                effectiveChatModel = (noToolChatModel != null) ? noToolChatModel : defaultChatModel;
                effectiveHasTools = false;
                log.info("Agent [{}] 声明 tools:[] → 使用无工具 ChatModel 变体", agentConfig.getName());
            } else if (agentConfig.getTools() != null && !agentConfig.getTools().isEmpty()) {
                // 非空白名单 → 本期暂不支持，降级为主 ChatModel
                effectiveChatModel = defaultChatModel;
                effectiveHasTools = globalHasTools;
                log.warn("Agent [{}] 声明 tools 白名单暂不支持本期实现，降级为主 ChatModel", agentConfig.getName());
            } else {
                // tools == null → 默认行为
                effectiveChatModel = defaultChatModel;
                effectiveHasTools = globalHasTools;
            }

            if (Boolean.TRUE.equals(agentConfig.getReactMode()) && effectiveHasTools) {
                log.info("Agent [{}] ReAct mode enabled, injecting ReAct prompt prefix. Original instruction length: {}",
                        agentConfig.getName(), instruction != null ? instruction.length() : 0);
                instruction = REACT_PROMPT_PREFIX + "\n\n---\n\n" + instruction;
            }

            LlmAgent.Builder agentBuilder = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new MySpringAI(effectiveChatModel, effectiveHasTools))
                    .instruction(instruction)
                    .outputKey(agentConfig.getOutputKey());

            // 设置 ReAct 循环最大步数
            // 无工具的纯对话智能体：强制 maxSteps=1，不允许 ADK 多轮调用 LLM
            // 有工具的智能体：使用配置的 maxSteps（需要多轮 Thought→Action→Observation）
            if (!effectiveHasTools) {
                log.info("Agent [{}] 无工具，强制 maxSteps=1，防止多轮自问自答", agentConfig.getName());
                agentBuilder.maxSteps(1);
            } else if (agentConfig.getMaxSteps() != null && agentConfig.getMaxSteps() > 0) {
                agentBuilder.maxSteps(agentConfig.getMaxSteps());
            }

            // 注入 exitLoop 工具：用于 LoopAgent 中 Reflexion 循环的语义级提前退出
            // 当 exit-loop-enabled: true 时，AgentNode 自动注入 ExitLoopTool
            // 【变更】tools:[] 时不注入 ExitLoopTool（强门控已在 Critic 层处理）
            if (Boolean.TRUE.equals(agentConfig.getExitLoopEnabled()) && effectiveHasTools) {
                agentBuilder.tools(FunctionTool.create(ExitLoopTool.class, "exitLoop"));
                log.info("Agent [{}] 已注入 exitLoop 工具，支持 Reflexion 循环语义级退出", agentConfig.getName());
            }

            LlmAgent llmAgent = agentBuilder.build();

            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
            // 双写 Builder 缓存：供 AgentWorkflowNode 中的高级工作流节点（Replan/Reflexion/Reflection）
            // 增强子 agent（追加 ExitLoopTool / Callback）后重新 build，覆盖上面的成品
            dynamicContext.getAgentBuilderGroup().put(agentConfig.getName(), agentBuilder);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}
