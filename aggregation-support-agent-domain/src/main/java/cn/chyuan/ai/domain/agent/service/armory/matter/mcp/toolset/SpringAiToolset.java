package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.toolset;

import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将一批 Spring AI {@link ToolCallback} 适配为 ADK {@link BaseToolset}，挂到 {@code LlmAgent} 上。
 * <p>
 * 背景：本项目的工具(RAG / AIOps / MCP 网关业务工具)都构建为 Spring AI ToolCallback，
 * 原本只挂在 {@code OpenAiChatModel.defaultOptions.toolCallbacks}。但 ADK 的工具执行链路只
 * 从 {@code LlmRequest.tools()}(即 agent 上挂载的 BaseToolset 产出)取工具，导致执行阶段抛
 * {@code No ToolCallback found for tool name: X}。
 * <p>
 * 本适配器让工具走 ADK 原生路径：装配期挂到 agent → 运行时进入 LlmRequest.tools() →
 * MessageConverter 转成 toolCallbacks 塞进 prompt.options → DefaultToolCallingManager 能正确执行。
 * <p>
 * 注意：close() 为空实现。MCP 客户端等底层资源的生命周期仍由 ChatModel.defaultOptions
 * 那条路径的 ToolCallback 管理，此处不重复关闭，避免误伤。
 *
 * @author chyuan
 * 2026/7/1
 */
@Slf4j
public class SpringAiToolset implements BaseToolset {

    private final List<ToolCallback> toolCallbacks;

    private SpringAiToolset(List<ToolCallback> toolCallbacks) {
        this.toolCallbacks = Objects.requireNonNull(toolCallbacks, "toolCallbacks cannot be null");
    }

    public static SpringAiToolset of(List<ToolCallback> toolCallbacks) {
        return new SpringAiToolset(toolCallbacks);
    }

    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
        List<BaseTool> tools = new ArrayList<>(toolCallbacks.size());
        for (ToolCallback callback : toolCallbacks) {
            try {
                tools.add(new SpringAiTool(callback));
            } catch (Exception e) {
                log.warn("包装 ToolCallback 为 BaseTool 失败，已跳过: {}", e.getMessage());
            }
        }
        log.info("SpringAiToolset 装配 {} 个工具: {}", tools.size(),
                tools.stream().map(BaseTool::name).toList());
        return Flowable.fromIterable(tools);
    }

    @Override
    public void close() {
        // 资源生命周期由 ChatModel.defaultOptions 的 ToolCallback 管理，此处不重复关闭
    }
}
