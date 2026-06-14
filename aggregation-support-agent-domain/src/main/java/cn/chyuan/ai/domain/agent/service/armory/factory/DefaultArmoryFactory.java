package cn.chyuan.ai.domain.agent.service.armory.factory;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.node.RootNode;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认的装配工厂
 *
 * @author chyuan @chyuan
 * 2025/12/17 08:16
 */
@Service
public class DefaultArmoryFactory {

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private RootNode rootNode;

    public StrategyHandler<ArmoryCommandEntity, DynamicContext, AiAgentRegisterVO> armoryStrategyHandler() {
        return rootNode;
    }

    public AiAgentRegisterVO getAiAgentRegisterVO(String agentId) {
        return applicationContext.getBean(agentId, AiAgentRegisterVO.class);
    }

    /**
     * 定义一个上下文对象，用于各个节点串联的时候，写入数据和使用数据
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        /**
         * LLM API
         */
        private OpenAiApi openAiApi;

        /**
         * LLM ChatModel
         */
        private ChatModel chatModel;

        /**
         * 无工具的 ChatModel 变体（同 openAiApi/model，defaultOptions 无 toolCallbacks）。
         * 供 tools: [] 声明的纯推理 agent（Planner/Critic）使用，避免规划阶段误调检索工具。
         * 仅当主 ChatModel 注册了工具时才构建，否则为 null（退化为主 ChatModel）。
         */
        private ChatModel noToolChatModel;

        /**
         * 是否注册了工具
         */
        private boolean hasTools;

        /**
         * 全局工具池：ChatModelNode 装配出的全部 ToolCallback（来自 tool-mcp-list + tool-skills-list）。
         * 供 AgentNode 按子 agent 的 tools 声明做白名单过滤，构建工具受限的 ChatModel 变体。
         */
        private List<ToolCallback> globalToolCallbacks;

        /**
         * ChatModel 模型名（用于按白名单构建受限 ChatModel 变体时复用同一模型）
         */
        private String chatModelName;

        /**
         * ChatModel 最大输出 token 数（用于按白名单构建受限 ChatModel 变体时复用同一限制）
         */
        private Integer chatModelMaxTokens;

        /**
         * 智能体配置组
         */
        @Builder.Default
        private Map<String, BaseAgent> agentGroup = new HashMap<>();

        /**
         * 智能体 Builder 组
         * <p>
         * 保留装配阶段的 LlmAgent.Builder，供后续高级工作流节点（Replan/Reflexion/Reflection）
         * 增强子 agent —— 追加 ExitLoopTool（触发 LoopAgent 条件退出）、beforeModel/afterAgent
         * Callback（Reflexion 跨迭代记忆注入、Critic/Evaluator 强门控）。增强后重新 build 覆盖
         * agentGroup 中的成品。
         * <p>
         * 说明：LlmAgent.Builder 可变可重复 build，未被增强的 agent 仍使用 agentGroup 中的默认成品。
         */
        @Builder.Default
        private Map<String, LlmAgent.Builder> agentBuilderGroup = new HashMap<>();

        /**
         * AgentEnhancementContext 缓存：按 agentName 累积回调（避免 *CallbackSync 单元素 setter 覆盖）。
         * 同一 agent 多次 enhance 时复用同一 context，累积的回调由 flush() 一次性注入 Builder。
         */
        @Builder.Default
        private Map<String, AgentEnhancementContext> agentEnhancementContextGroup = new HashMap<>();

        /**
         * 增强子 agent：从 agentBuilderGroup 取出 Builder，应用增强逻辑后重新 build，覆盖 agentGroup 成品
         *
         * @param agentName 子 agent 名称
         * @param enhancer  增强函数（对 Builder 追加 tools / callbacks 等）
         * @return true 表示增强并覆盖成功；false 表示该 agent 无 Builder 缓存（按原成品使用）
         */
        public boolean enhanceAgent(String agentName, java.util.function.Consumer<AgentEnhancementContext> enhancer) {
            LlmAgent.Builder builder = agentBuilderGroup.get(agentName);
            if (builder == null) {
                return false;
            }
            AgentEnhancementContext ctx = agentEnhancementContextGroup
                    .computeIfAbsent(agentName, k -> new AgentEnhancementContext(builder));
            enhancer.accept(ctx);
            ctx.flush();
            agentGroup.put(agentName, builder.build());
            return true;
        }

        @Builder.Default
        private AtomicInteger currentStepIndex = new AtomicInteger(0);

        private AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow;

        @Builder.Default
        private Map<String, Object> dataObjects = new HashMap<>();

        public <T> void setValue(String key, T value) {
            dataObjects.put(key, value);
        }

        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }

        public List<BaseAgent> queryAgentList(List<String> agentNames) {
            if (agentNames == null || agentNames.isEmpty() || agentGroup == null) {
                return Collections.emptyList();
            }

            List<BaseAgent> agents = new ArrayList<>();
            for (String name : agentNames) {
                BaseAgent agent = agentGroup.get(name);
                if (agent != null) {
                    agents.add(agent);
                }
            }

            return agents;
        }

        public void addCurrentStepIndex() {
            currentStepIndex.incrementAndGet();
        }

        public int getCurrentStepIndex() {
            return currentStepIndex.get();
        }

    }

}
