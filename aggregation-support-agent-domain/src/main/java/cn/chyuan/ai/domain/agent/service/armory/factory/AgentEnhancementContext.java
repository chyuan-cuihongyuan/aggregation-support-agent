package cn.chyuan.ai.domain.agent.service.armory.factory;

import com.google.adk.agents.Callbacks;
import com.google.adk.agents.LlmAgent;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 增强上下文 —— 受限的 LlmAgent.Builder 包装。
 * <p>
 * 只允许追加工具与回调（addTool / *CallbackSync），<b>禁止</b>修改 name/model/instruction 等核心属性，
 * 防止 {@code enhanceAgent} 的调用方误改 agent 身份导致装配错乱（#10）。
 * <p>
 * 放在 factory 包与 {@link DefaultArmoryFactory.DynamicContext} 同层，避免 factory ↔ workflow 循环依赖。
 *
 * @author chyuan
 * @since 2026-06-13
 */
public class AgentEnhancementContext {

    private final LlmAgent.Builder builder;

    public AgentEnhancementContext(LlmAgent.Builder builder) {
        this.builder = builder;
    }

    /**
     * 追加工具（保留现有 agent 级工具，避免 setter 覆盖丢失）。
     */
    public AgentEnhancementContext addTool(Object tool) {
        List<Object> tools = new ArrayList<>(builder.build().toolsUnion());
        tools.add(tool);
        builder.tools(tools);
        return this;
    }

    public AgentEnhancementContext afterModelCallbackSync(Callbacks.AfterModelCallbackSync callback) {
        builder.afterModelCallbackSync(callback);
        return this;
    }

    public AgentEnhancementContext beforeModelCallbackSync(Callbacks.BeforeModelCallbackSync callback) {
        builder.beforeModelCallbackSync(callback);
        return this;
    }

    public AgentEnhancementContext afterAgentCallbackSync(Callbacks.AfterAgentCallbackSync callback) {
        builder.afterAgentCallbackSync(callback);
        return this;
    }
}
