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
            You are working with optional tool-calling capability in ReAct (Reasoning + Acting) mode.

            ## Core Principle: Only call tools when truly necessary
            You have access to tools, but you should NOT call them for every request. Many questions can be answered directly using your own knowledge.

            ## When to use tools:
            - The user explicitly requests data, information, or actions that require external systems
            - The question involves real-time data, business queries, or system operations that you cannot answer from your own knowledge
            - The user's intent clearly maps to a specific tool's capability

            ## When NOT to use tools:
            - General conversation, greetings, chitchat
            - Questions you can answer from your own knowledge (general knowledge, explanations, advice, etc.)
            - Opinion, creative writing, or reasoning tasks
            - When the user is just chatting or asking simple questions

            ## ReAct Loop (only when tools are needed):
            Thought: Analyze whether this request requires tool usage. If not, answer directly.
            Action: Call the appropriate tool only when you've determined it's necessary
            Observation: (The system will return the tool execution result)

            You can perform multiple rounds of Thought→Action→Observation until you have enough information.

            ## Rules:
            - First determine: does this request need a tool? If NO, answer directly without any tool call
            - When tools ARE needed: Always output Thought first (explain why you need the tool)
            - Do not call tools unnecessarily — each tool call has a cost in latency and resources
            - If you already have enough information, give your answer directly without unnecessary tool calls
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

            // 设置 ReAct 循环最大步数，防止 LLM 空响应导致无限循环
            if (agentConfig.getMaxSteps() != null && agentConfig.getMaxSteps() > 0) {
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
