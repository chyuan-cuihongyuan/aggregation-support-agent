package cn.chyuan.ai.domain.agent.service.armory.node;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.ScopedToolCallback;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import cn.chyuan.ai.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import cn.chyuan.ai.domain.agent.service.armory.support.ObservationWiring;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** spring-ai GenAI 指标装配（SELFLOOP2 loop-212）：actuator registry 缺席时回退 NOOP */
    @Autowired(required = false)
    private ObservationRegistry observationRegistry;

    // LLM 重试显式化（SELFLOOP4 loop-412，工单 0622/0623）：手工 builder 绕过自动装配的
    // RetryTemplate（缺席时落默认 10 次/3min 退避），此处显式装配并外置——
    // 交互口径收敛 3 次、退避上限 10s 消灭长尾；参数语义对照 Spring AI 官方属性表
    @Value("${spring.ai.retry.max-attempts:3}")
    private int retryMaxAttempts;

    @Value("${spring.ai.retry.backoff.initial-interval-ms:2000}")
    private long retryInitialIntervalMs;

    @Value("${spring.ai.retry.backoff.multiplier:5.0}")
    private double retryMultiplier;

    @Value("${spring.ai.retry.backoff.max-interval-ms:10000}")
    private long retryMaxIntervalMs;

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

        // 构建mcp服务（工厂）— 使用 Set 按工具名去重，防止多个 Provider 注册同名工具
        List<ToolCallback> toolCallbackList = new ArrayList<>();
        Set<String> registeredToolNames = new HashSet<>();

        if (null != toolMcpList && !toolMcpList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
                try {
                    TooMcpCreateService tooMcpCreateService = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
                    ToolCallback[] toolCallbacks = tooMcpCreateService.buildToolCallback(toolMcp);
                    if (toolCallbacks == null || toolCallbacks.length == 0) {
                        log.error("MCP 工具初始化返回空，该 MCP 服务无任何工具可用（可能是 SSE 初始化失败耗尽重试）。" +
                                "agent: {}, toolMcp: {}", aiAgentConfigTableVO.getAppName(), toolMcpName(toolMcp));
                    }
                    for (ToolCallback toolCallback : ScopedToolCallback.wrapAll(toolCallbacks)) {
                        String toolName = toolCallback.getToolDefinition().name();
                        if (registeredToolNames.add(toolName)) {
                            toolCallbackList.add(toolCallback);
                        } else {
                            log.warn("工具 [{}] 重复注册，已跳过。agent: {}", toolName, aiAgentConfigTableVO.getAppName());
                        }
                    }
                } catch (Exception e) {
                    log.error("MCP 工具初始化失败，跳过该工具。agent: {}, toolMcp: {}, 错误: {}",
                            aiAgentConfigTableVO.getAppName(), toolMcpName(toolMcp), e.getMessage());
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
                .observationRegistry(ObservationWiring.effective(observationRegistry))
                .defaultOptions(optionsBuilder.build())
                .retryTemplate(LlmRetrySupport.buildRetryTemplate(retryMaxAttempts, retryInitialIntervalMs,
                        retryMultiplier, retryMaxIntervalMs))
                .build();

        dynamicContext.setChatModel(chatModel);
        dynamicContext.setHasTools(!toolCallbackList.isEmpty());
        // 显式传递工具列表，供 AgentNode 包装成 SpringAiToolset 挂到 LlmAgent 上走 ADK 原生工具路径
        dynamicContext.setToolCallbacks(toolCallbackList);

        // 打印最终注册的工具名清单，便于排查 "No ToolCallback found" 类问题
        if (toolCallbackList.isEmpty()) {
            log.warn("Agent [{}] 未注册任何工具（toolCallbacks 为空），将作为纯对话智能体运行。",
                    aiAgentConfigTableVO.getAppName());
        } else {
            String toolNames = toolCallbackList.stream()
                    .map(cb -> cb.getToolDefinition().name())
                    .collect(java.util.stream.Collectors.joining(", "));
            log.info("Agent [{}] 注册了 {} 个工具: [{}]",
                    aiAgentConfigTableVO.getAppName(), toolCallbackList.size(), toolNames);
        }

        return router(requestParameter, dynamicContext);
    }

    /**
     * 提取 toolMcp 的可读名称（local 用 name，sse/stdio 用 name），用于日志输出。
     */
    private String toolMcpName(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        try {
            if (toolMcp.getLocal() != null && toolMcp.getLocal().getName() != null) {
                return "local:" + toolMcp.getLocal().getName();
            }
            if (toolMcp.getSse() != null && toolMcp.getSse().getName() != null) {
                return "sse:" + toolMcp.getSse().getName();
            }
            if (toolMcp.getStdio() != null && toolMcp.getStdio().getName() != null) {
                return "stdio:" + toolMcp.getStdio().getName();
            }
        } catch (Exception ignored) {
            // 名称提取失败不影响主流程
        }
        return "unknown";
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }


}
