package cn.chyuan.ai.domain.agent.service.armory.node;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.chyuan.ai.domain.agent.service.armory.matter.patch.MySpringAI;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
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

            You can perform multiple rounds of Thought→Action→Observation until you have enough information.

            ## Rules:
            - When in doubt about whether to use a tool, prefer using it
            - Always use tools for factual queries about data in the knowledge base
            - Do not substitute your own knowledge for tool results on factual questions
            - Your final response should contain only the substantive content, do not include labels like "Final Answer:" or "Thought:"
            """;

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        ChatModel chatModel = dynamicContext.getChatModel();
        boolean hasTools = dynamicContext.isHasTools();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            String instruction = agentConfig.getInstruction();

            if (Boolean.TRUE.equals(agentConfig.getReactMode()) && hasTools) {
                log.info("Agent [{}] ReAct mode enabled, injecting ReAct prompt prefix. Original instruction length: {}",
                        agentConfig.getName(), instruction != null ? instruction.length() : 0);
                instruction = REACT_PROMPT_PREFIX + "\n\n---\n\n" + instruction;
            }

            LlmAgent.Builder agentBuilder = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new MySpringAI(chatModel, hasTools))
                    .instruction(instruction)
                    .outputKey(agentConfig.getOutputKey());

            // 设置 ReAct 循环最大步数
            // 无工具的纯对话智能体：强制 maxSteps=1，不允许 ADK 多轮调用 LLM
            // 有工具的智能体：使用配置的 maxSteps（需要多轮 Thought→Action→Observation）
            if (!hasTools) {
                log.info("Agent [{}] 无工具，强制 maxSteps=1，防止多轮自问自答", agentConfig.getName());
                agentBuilder.maxSteps(1);
            } else if (agentConfig.getMaxSteps() != null && agentConfig.getMaxSteps() > 0) {
                agentBuilder.maxSteps(agentConfig.getMaxSteps());
            }

            LlmAgent llmAgent = agentBuilder.build();

            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}
