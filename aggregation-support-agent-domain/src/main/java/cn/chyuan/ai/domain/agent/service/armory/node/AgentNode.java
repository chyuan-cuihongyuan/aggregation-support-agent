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
            You are working in ReAct (Reasoning + Acting) mode. For each task, strictly follow this loop:

            Thought: Analyze the current situation and reason about what to do next
            Action: Call an available tool (choose the most appropriate one for your role)
            Observation: (The system will automatically return the tool execution result)

            You can perform multiple rounds of Thought→Action→Observation until you have enough information.

            When you have sufficient information, provide your conclusion with Final Answer.

            Rules:
            - Always output Thought first (your reasoning process)
            - Do not skip reasoning and call tools directly — explain why you need the tool
            - If you already have enough information, give Final Answer directly without unnecessary tool calls
            - Your Final Answer should contain only the substantive content, do not include the label "Final Answer:" itself
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

            LlmAgent llmAgent = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new MySpringAI(chatModel))
                    .instruction(instruction)
                    .outputKey(agentConfig.getOutputKey())
                    .build();

            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}
