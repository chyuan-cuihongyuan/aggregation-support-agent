package cn.chyuan.ai.config;

import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server.DateTimeTools;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server.InternalDocsTools;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server.QueryLogsTools;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server.QueryMetricsTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AIOps 工具集注册配置 — 将所有运维分析工具注册为 Spring AI 的 ToolCallbackProvider
 * <p>
 * 注册的工具：
 * <ul>
 *   <li>DateTimeTools - 获取当前时间</li>
 *   <li>InternalDocsTools - 从知识库检索运维文档</li>
 *   <li>QueryMetricsTools - 查询 Prometheus 告警</li>
 *   <li>QueryLogsTools - 查询 CLS 日志</li>
 * </ul>
 * 在 YAML 智能体配置中通过 local name 引用: aiopsToolCallbackProvider
 */
@Configuration
public class AIOpsToolConfig {

    @Bean("aiopsToolCallbackProvider")
    public ToolCallbackProvider aiopsToolCallbackProvider(
            DateTimeTools dateTimeTools,
            InternalDocsTools internalDocsTools,
            QueryMetricsTools queryMetricsTools,
            QueryLogsTools queryLogsTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools)
                .build();
    }

    /**
     * RAG 文档检索工具注册 — 仅包含文档搜索工具，用于 RAG 问答智能体
     */
    @Bean("ragToolCallbackProvider")
    public ToolCallbackProvider ragToolCallbackProvider(InternalDocsTools internalDocsTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(internalDocsTools)
                .build();
    }

}
