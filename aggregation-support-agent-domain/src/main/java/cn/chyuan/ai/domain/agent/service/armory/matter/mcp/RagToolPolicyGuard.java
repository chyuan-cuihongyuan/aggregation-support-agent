package cn.chyuan.ai.domain.agent.service.armory.matter.mcp;

import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * RAG 知识库智能体工具策略守卫
 * <p>
 * 架构约束：RAG 知识库智能体只应做知识库检索，不允许调用 MCP 网关（外部业务系统）。
 * 在智能体装配期（启动阶段）校验，若 RAG 智能体被配了指向 MCP 网关的 SSE 工具，
 * 抛出 {@link AppException}（E0002），使该智能体拒绝装配、不注册。
 * <p>
 * 通过 agent-id 精确识别 RAG 智能体，superAgent(500001) 等非 RAG 智能体不受影响。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class RagToolPolicyGuard {

    /**
     * 受 MCP 网关工具约束的 RAG 知识库智能体 agent-id 白名单。
     * <p>
     * 含 ragChatAgent(200001)、qianwenRagChatAgent(200003)、deepseekRagChatAgent(200004)。
     * 新增 RAG 知识库智能体时必须同步在此添加 agent-id，否则约束会漏覆盖。
     */
    private static final Set<String> RAG_AGENT_IDS = Set.of("200001", "200003", "200004");

    /** MCP 网关特征关键词（大小写不敏感匹配）。网关工具的 name/baseUri/sseEndpoint 均含此关键词。 */
    private static final String GATEWAY_KEYWORD = "gateway";

    /**
     * 校验智能体的 MCP 工具配置是否符合 RAG 策略约束。
     * <p>
     * RAG 智能体若挂载了指向 MCP 网关的 SSE 工具，抛出 {@link AppException}（E0002）。
     * 非 RAG 智能体直接放行。
     *
     * @param agentId     智能体 ID
     * @param appName     应用名（日志用）
     * @param toolMcpList MCP 工具列表
     */
    public void enforce(String agentId, String appName,
                        List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList) {
        if (!isRagAgent(agentId)) {
            return;
        }
        if (toolMcpList == null || toolMcpList.isEmpty()) {
            return;
        }
        for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
            if (isMcpGatewaySseTool(toolMcp)) {
                log.error("RAG 智能体 [{}]（agentId={}）不允许挂载 MCP 网关 SSE 工具 [{}]，拒绝装配。",
                        appName, agentId, describeTool(toolMcp));
                throw new AppException(ResponseCode.E0002.getCode(),
                        String.format("RAG智能体[%s]不允许挂载MCP网关SSE工具，agentId=%s", appName, agentId));
            }
        }
    }

    /** 是否为 RAG 知识库智能体 */
    private boolean isRagAgent(String agentId) {
        return agentId != null && RAG_AGENT_IDS.contains(agentId);
    }

    /**
     * 判断 ToolMcp 是否为指向 MCP 网关的 SSE 工具。
     * <p>
     * 判定条件：sse 参数非空，且 name / baseUri / sseEndpoint 任一含网关关键词 gateway，
     * 或 baseUri / sseEndpoint 含网关端口 8099。三重特征"或"逻辑，空值安全、大小写不敏感。
     */
    private boolean isMcpGatewaySseTool(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        if (toolMcp == null || toolMcp.getSse() == null) {
            return false;
        }
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters sse = toolMcp.getSse();
        // MCP 网关工具的 name / baseUri / sseEndpoint 任一含 gateway 关键词即判定（如 mcp-gateway-business、/api-gateway/...）。
        // 不用端口号匹配，避免子串误判（如 10.0.180.99、:80990 等含 "8099" 的合法地址被误拦）。
        return StringUtils.containsIgnoreCase(sse.getName(), GATEWAY_KEYWORD)
                || StringUtils.containsIgnoreCase(sse.getBaseUri(), GATEWAY_KEYWORD)
                || StringUtils.containsIgnoreCase(sse.getSseEndpoint(), GATEWAY_KEYWORD);
    }

    /** 生成工具描述（日志用，不含敏感 api_key） */
    private String describeTool(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        if (toolMcp.getSse() != null) {
            return "sse:" + toolMcp.getSse().getName();
        }
        if (toolMcp.getLocal() != null) {
            return "local:" + toolMcp.getLocal().getName();
        }
        if (toolMcp.getStdio() != null) {
            return "stdio:" + toolMcp.getStdio().getName();
        }
        return "unknown";
    }

}
