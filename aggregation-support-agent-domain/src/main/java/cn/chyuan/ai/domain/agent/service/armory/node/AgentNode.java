package cn.chyuan.ai.domain.agent.service.armory.node;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.chyuan.ai.domain.agent.service.armory.factory.AgentEnhancementContext;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.chyuan.ai.domain.agent.service.armory.matter.patch.MySpringAI;
import cn.chyuan.ai.domain.agent.service.armory.matter.tools.ExitLoopTool;
import com.google.adk.agents.CallbackContext;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.FunctionTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    private static final String WILDCARD_TOOL = "*";

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        ChatModel defaultChatModel = dynamicContext.getChatModel();
        ChatModel noToolChatModel = dynamicContext.getNoToolChatModel();
        boolean globalHasTools = dynamicContext.isHasTools();
        List<ToolCallback> globalToolCallbacks = dynamicContext.getGlobalToolCallbacks();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            String instruction = agentConfig.getInstruction();

            // 【安全策略】子 agent 工具调用必须显式声明（配置即权限，未配置不允许调用）：
            // - tools == null：未配置 → 按安全策略不注入任何工具（禁止调用任何工具）
            // - tools == []：显式空列表 → 无工具变体（Planner/Critic 等纯推理 agent）
            // - tools == ["*"]：通配符 → 注入工具池全部工具（显式声明使用全部）
            // - tools == ["t1","t2"]：白名单 → 仅注入工具池中匹配的工具（按名过滤）
            ChatModel effectiveChatModel;
            boolean effectiveHasTools;
            List<String> declaredTools = agentConfig.getTools();

            if (declaredTools == null) {
                // 未配置 tools → 安全策略：禁止任何工具调用
                effectiveChatModel = (noToolChatModel != null) ? noToolChatModel : defaultChatModel;
                effectiveHasTools = false;
                log.warn("Agent [{}] 未声明 tools 字段，按安全策略不注入任何工具（未配置不允许调用）", agentConfig.getName());
            } else if (declaredTools.isEmpty()) {
                // 显式空列表 → 无工具变体
                effectiveChatModel = (noToolChatModel != null) ? noToolChatModel : defaultChatModel;
                effectiveHasTools = false;
                log.info("Agent [{}] 声明 tools:[] → 使用无工具 ChatModel 变体", agentConfig.getName());
            } else if (declaredTools.contains(WILDCARD_TOOL)) {
                // 通配符 "*" → 注入工具池全部工具
                effectiveChatModel = defaultChatModel;
                effectiveHasTools = globalHasTools;
                log.info("Agent [{}] 声明 tools:[*] → 使用全部工具 ChatModel", agentConfig.getName());
            } else {
                // 白名单 → 仅注入声明的工具
                List<ToolCallback> filteredTools = filterToolCallbacks(globalToolCallbacks, declaredTools);
                if (filteredTools.isEmpty()) {
                    log.warn("Agent [{}] 声明工具 {} 在工具池中未匹配到任何工具，按安全策略不注入工具", agentConfig.getName(), declaredTools);
                    effectiveChatModel = (noToolChatModel != null) ? noToolChatModel : defaultChatModel;
                    effectiveHasTools = false;
                } else {
                    effectiveChatModel = buildFilteredChatModel(dynamicContext, filteredTools);
                    effectiveHasTools = true;
                    log.info("Agent [{}] 声明白名单 tools:{} → 注入 {} 个匹配工具: {}",
                            agentConfig.getName(), declaredTools, filteredTools.size(),
                            filteredTools.stream().map(t -> t.getToolDefinition().name()).collect(Collectors.toList()));
                }
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

            // 添加 afterAgentCallback 来处理 outputKey：清理 markdown 代码块后重新存入 session state
            // ADK 的 outputKey 已自动将 agent 输出写入 state，但可能包含 ```json ... ``` 包裹
            // 此回调在 agent 执行后读取 state 中的值，去除 markdown 标记后重新写回
            if (agentConfig.getOutputKey() != null && !agentConfig.getOutputKey().isEmpty()) {
                final String outputKey = agentConfig.getOutputKey();
                agentBuilder.afterAgentCallback(List.of((com.google.adk.agents.Callbacks.AfterAgentCallbackSync) callbackContext -> {
                    try {
                        Object raw = callbackContext.state().get(outputKey);
                        if (raw instanceof String output && !output.isEmpty()) {
                            // 去除 markdown 代码块标记（如 ```json 和 ```）
                            String cleanedOutput = output.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)```\\s*$", "").trim();
                            if (!cleanedOutput.equals(output)) {
                                callbackContext.state().put(outputKey, cleanedOutput);
                                log.info("Agent [{}] outputKey [{}] 已清理 markdown 代码块，内容长度: {} -> {}",
                                        agentConfig.getName(), outputKey, output.length(), cleanedOutput.length());
                            }
                        }
                    } catch (Exception e) {
                        log.error("Agent [{}] 处理 outputKey [{}] 时发生错误: {}",
                                agentConfig.getName(), outputKey, e.getMessage(), e);
                    }
                    return java.util.Optional.empty();
                }));
            }

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
            // 【解耦】exitLoop 是工作流控制工具，与业务工具池(tools)正交：
            //   - 即使 tools:[]（无业务工具），exit-loop-enabled:true 仍注入 ExitLoopTool
            //   - 这样退出决策 agent 可以声明 tools:[] 而不影响循环退出能力
            if (Boolean.TRUE.equals(agentConfig.getExitLoopEnabled())) {
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

    /**
     * 从全局工具池中按声明的工具名过滤出匹配的 ToolCallback（配置即权限，未声明的工具一律不可调用）
     */
    private List<ToolCallback> filterToolCallbacks(List<ToolCallback> pool, List<String> declared) {
        if (pool == null || pool.isEmpty() || declared == null || declared.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> declaredSet = new HashSet<>(declared);
        List<ToolCallback> filtered = new ArrayList<>();
        for (ToolCallback tc : pool) {
            if (declaredSet.contains(tc.getToolDefinition().name())) {
                filtered.add(tc);
            }
        }
        return filtered;
    }

    /**
     * 基于全局工具池中的指定子集构建工具受限的 ChatModel 变体（复用同一 openAiApi/model/maxTokens）
     */
    private ChatModel buildFilteredChatModel(DefaultArmoryFactory.DynamicContext dynamicContext, List<ToolCallback> filteredTools) {
        OpenAiApi openAiApi = dynamicContext.getOpenAiApi();
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(dynamicContext.getChatModelName())
                .toolCallbacks(filteredTools);
        if (dynamicContext.getChatModelMaxTokens() != null && dynamicContext.getChatModelMaxTokens() > 0) {
            optionsBuilder.maxTokens(dynamicContext.getChatModelMaxTokens());
        }
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(optionsBuilder.build())
                .build();
    }

}
