package cn.chyuan.ai.domain.agent.service.armory.node;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.RagToolPolicyGuard;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.ScopedToolCallback;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import cn.chyuan.ai.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class ChatModelNode extends AbstractArmorySupport {

    @Resource
    private AgentNode agentNode;

    @Resource
    private DefaultMcpClientFactory defaultMcpClientFactory;

    @Resource
    private ToolSkillsCreateService toolSkillsCreateService;

    @Resource
    private RagToolPolicyGuard ragToolPolicyGuard;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        // 获取上下文对象
        OpenAiApi openAiApi = dynamicContext.getOpenAiApi();

        // 获取配置对象
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();
        List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList = chatModelConfig.getToolMcpList();
        List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList = chatModelConfig.getToolSkillsList();

        // 【工具策略守卫】RAG 智能体不允许挂载 MCP 网关 SSE 工具，违规时启动期拒绝装配。
        // 置于 MCP 循环之前，确保违规异常不进入循环内的工具级 catch，能逃逸到顶层装配 catch 以拒绝该智能体。
        ragToolPolicyGuard.enforce(
                aiAgentConfigTableVO.getAgent().getAgentId(),
                aiAgentConfigTableVO.getAppName(),
                toolMcpList);

        // 构建mcp服务（工厂）— 使用 Set 按工具名去重，防止多个 Provider 注册同名工具
        List<ToolCallback> toolCallbackList = new ArrayList<>();
        Set<String> registeredToolNames = new HashSet<>();

        if (null != toolMcpList && !toolMcpList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
                try {
                    TooMcpCreateService tooMcpCreateService = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
                    ToolCallback[] toolCallbacks = tooMcpCreateService.buildToolCallback(toolMcp);
                    for (ToolCallback toolCallback : ScopedToolCallback.wrapAll(toolCallbacks)) {
                        String toolName = toolCallback.getToolDefinition().name();
                        if (registeredToolNames.add(toolName)) {
                            toolCallbackList.add(toolCallback);
                        } else {
                            log.warn("工具 [{}] 重复注册，已跳过。agent: {}", toolName, aiAgentConfigTableVO.getAppName());
                        }
                    }
                } catch (Exception e) {
                    log.error("MCP 工具初始化失败，跳过该工具。agent: {}, 错误: {}",
                            aiAgentConfigTableVO.getAppName(), e.getMessage());
                }
            }
        }

        // 构建skills服务
        if (null != toolSkillsList && !toolSkillsList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills : toolSkillsList) {
                ToolCallback[] toolCallbacks = toolSkillsCreateService.buildToolCallback(toolSkills);
                for (ToolCallback toolCallback : ScopedToolCallback.wrapAll(toolCallbacks)) {
                    String toolName = toolCallback.getToolDefinition().name();
                    if (registeredToolNames.add(toolName)) {
                        toolCallbackList.add(toolCallback);
                    } else {
                        log.warn("Skills 工具 [{}] 重复注册，已跳过。agent: {}", toolName, aiAgentConfigTableVO.getAppName());
                    }
                }
            }
        }

        // 构建对话模型选项
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(chatModelConfig.getModel())
                .toolCallbacks(toolCallbackList);

        // 设置最大输出 token 数，防止模型无限生成
        if (chatModelConfig.getMaxTokens() != null && chatModelConfig.getMaxTokens() > 0) {
            optionsBuilder.maxTokens(chatModelConfig.getMaxTokens());
        }

        // 构建对话模型
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(optionsBuilder.build())
                .build();

        dynamicContext.setChatModel(chatModel);
        dynamicContext.setHasTools(!toolCallbackList.isEmpty());

        // 【新增】构建无工具 ChatModel 变体（同 openAiApi/model，defaultOptions 无 toolCallbacks）
        // 供 Planner/Critic 等纯推理 agent 使用，避免规划阶段误调用检索工具
        if (!toolCallbackList.isEmpty()) {
            OpenAiChatOptions.Builder noToolOptionsBuilder = OpenAiChatOptions.builder()
                    .model(chatModelConfig.getModel());
            if (chatModelConfig.getMaxTokens() != null && chatModelConfig.getMaxTokens() > 0) {
                noToolOptionsBuilder.maxTokens(chatModelConfig.getMaxTokens());
            }
            ChatModel noToolChatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(noToolOptionsBuilder.build())
                    .build();
            dynamicContext.setNoToolChatModel(noToolChatModel);
            log.info("【工具隔离】构建无工具 ChatModel 变体，供 tools:[] 声明的纯推理 agent 使用");
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }

}
