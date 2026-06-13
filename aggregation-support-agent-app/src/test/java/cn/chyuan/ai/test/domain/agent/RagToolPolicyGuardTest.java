package cn.chyuan.ai.test.domain.agent;

import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.RagToolPolicyGuard;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * RAG 工具策略守卫单元测试
 * <p>
 * 纯 POJO 测试（直接 new 守卫实例，不依赖 Spring 容器），覆盖：
 * 拦截（RAG 智能体 + MCP 网关 SSE 工具）、放行（当前真实配置 / 非 RAG 智能体 / 非网关 SSE）、边界。
 *
 * @author chyuan
 */
public class RagToolPolicyGuardTest {

    private final RagToolPolicyGuard guard = new RagToolPolicyGuard();

    // ===== 拦截：RAG 智能体 + MCP 网关 SSE 工具 → 抛 AppException(E0002) =====

    @Test
    public void ragAgent_gatewaySseByName_shouldReject() {
        // name 含 gateway（同时 baseUri 含 8099、sseEndpoint 含 gateway）
        assertReject("200001",
                sse("mcp-gateway-business", "http://127.0.0.1:8099", "/api-gateway/gateway_business/mcp/sse?api_key=xxx"));
    }

    @Test
    public void ragAgent_portCoincideWithoutGatewayKeyword_shouldPass() {
        // 仅 baseUri 含 8099 但 name/baseUri/sseEndpoint 均不含 gateway → 视为非网关工具放行（避免子串误判）
        assertPass("200001", sse("biz-tool", "http://10.0.0.5:8099", "/sse"));
    }

    @Test
    public void qianwenRagAgent_gatewaySseByEndpoint_shouldReject() {
        // 千问 RAG(200003) + sseEndpoint 含 gateway → 命中
        assertReject("200003", sse("biz", "http://x.com", "/api-gateway/gateway_business/mcp/sse"));
    }

    @Test
    public void deepseekRagAgent_gatewaySse_shouldReject() {
        // deepseekRagChatAgent(200004) 同为 RAG 知识库智能体，必须受约束
        assertReject("200004", sse("mcp-gateway-business", "http://127.0.0.1:8099", "/api-gateway/gateway_business/mcp/sse"));
    }

    @Test
    public void ragAgent_gatewayKeywordCaseInsensitive_shouldReject() {
        // 大小写混合关键词 MCP-GATEWAY-Business 仍命中（大小写不敏感）
        assertReject("200001", sse("MCP-GATEWAY-Business", "http://h:1234", "/sse"));
    }

    // ===== 放行：当前真实配置 / 非 RAG 智能体 / 非网关 SSE =====

    @Test
    public void ragAgent_localTool_shouldPass() {
        // RAG 智能体当前真实配置：仅 local 知识库工具
        assertPass("200001", local("ragToolCallbackProvider"));
    }

    @Test
    public void qianwenRagAgent_localTool_shouldPass() {
        assertPass("200003", local("ragToolCallbackProvider"));
    }

    @Test
    public void superAgent_withRagAndGateway_shouldPass() {
        // superAgent(500001) 同时挂 RAG local + AIOps local + MCP 网关 SSE，但非 RAG 智能体 → 必须放行（不误伤）
        assertPass("500001",
                local("ragToolCallbackProvider"),
                local("aiopsToolCallbackProvider"),
                sse("mcp-gateway-business", "http://127.0.0.1:8099", "/api-gateway/gateway_business/mcp/sse"));
    }

    @Test
    public void mcpGatewayAgent_gatewaySse_shouldPass() {
        // mcpGatewayAgent(400001) 本就应使用 MCP 网关工具
        assertPass("400001", sse("mcp-gateway-business", "http://127.0.0.1:8099", "/api-gateway/gateway_business/mcp/sse"));
    }

    @Test
    public void aiopsAgent_gatewaySse_shouldPass() {
        // AIOps(200002) 不在 RAG 白名单
        assertPass("200002", sse("mcp-gateway-business", "http://127.0.0.1:8099", "/sse"));
    }

    @Test
    public void ragAgent_thirdPartySse_shouldPass() {
        // 非网关第三方 SSE（不含 gateway/8099）放行 —— 约束只针对 MCP 网关
        assertPass("200001", sse("baidu-search", "https://appbuilder.baidu.com", "/v2/ai_search/mcp/sse?api_key=xxx"));
    }

    // ===== 边界 =====

    @Test
    public void ragAgent_emptyList_shouldPass() {
        guard.enforce("200001", "testApp", Collections.emptyList());
    }

    @Test
    public void ragAgent_nullList_shouldPass() {
        guard.enforce("200001", "testApp", null);
    }

    @Test
    public void nullAgentId_shouldPass() {
        assertPass(null, sse("mcp-gateway-business", "http://127.0.0.1:8099", "/sse"));
    }

    // ===== 辅助方法 =====

    /** 预期抛 AppException(E0002)；未抛则测试失败。 */
    private void assertReject(String agentId, AiAgentConfigTableVO.Module.ChatModel.ToolMcp... tools) {
        try {
            guard.enforce(agentId, "testApp", List.of(tools));
            Assert.fail("预期抛 AppException(E0002)，但未抛出。agentId=" + agentId);
        } catch (AppException e) {
            Assert.assertEquals("违规应抛 E0002", "E0002", e.getCode());
        }
    }

    /** 预期不抛异常；抛出则测试失败。 */
    private void assertPass(String agentId, AiAgentConfigTableVO.Module.ChatModel.ToolMcp... tools) {
        try {
            guard.enforce(agentId, "testApp", List.of(tools));
        } catch (Exception e) {
            Assert.fail("不应抛异常，但抛出: " + e.getMessage());
        }
    }

    private AiAgentConfigTableVO.Module.ChatModel.ToolMcp sse(String name, String baseUri, String sseEndpoint) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp();
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters sse = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters();
        sse.setName(name);
        sse.setBaseUri(baseUri);
        sse.setSseEndpoint(sseEndpoint);
        toolMcp.setSse(sse);
        return toolMcp;
    }

    private AiAgentConfigTableVO.Module.ChatModel.ToolMcp local(String name) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp();
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters local = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters();
        local.setName(name);
        toolMcp.setLocal(local);
        return toolMcp;
    }

}
