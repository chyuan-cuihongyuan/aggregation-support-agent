package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.impl;

import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.time.Duration;

@Slf4j
@Service
public class SSEToolMcpCreateService implements TooMcpCreateService {

    private static final int INITIALIZE_MAX_ATTEMPTS = 5;
    private static final long INITIALIZE_RETRY_INTERVAL_MS = 3000L;
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 3000L;

    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters sseConfig = toolMcp.getSse();

        // http://appbuilder.baidu.com/v2/ai_search/mcp/sse?api_key=bce-v3/ALTAK-JFZXXLpfxhAutDQvJ32Ei/4492c1879b8c2f0df4612ef5b4a52df1c1fba9f7

        String originalBaseUri = sseConfig.getBaseUri();
        String baseUri = originalBaseUri;
        String sseEndpoint = sseConfig.getSseEndpoint();

        if (StringUtils.isBlank(sseEndpoint)) {
            URL url = new URL(originalBaseUri);

            String protocol = url.getProtocol();
            String host = url.getHost();
            int port = url.getPort();

            String baseUrl = port == -1 ? protocol + "://" + host : protocol + "://" + host + ":" + port;

            int index = originalBaseUri.indexOf(baseUrl);
            if (index != -1) {
                sseEndpoint = originalBaseUri.substring(index + baseUrl.length());
            }

            baseUri = baseUrl;
        }

        sseEndpoint = StringUtils.isBlank(sseEndpoint) ? "/sse" : sseEndpoint;
        // requestTimeout 覆盖 initialize() + callTool()，工具调用需较长超时；
        // connectTimeout 单独 capped 在 3s，不受此值影响
        long requestTimeoutMs = sseConfig.getRequestTimeout() == null ? 60000L : sseConfig.getRequestTimeout();
        long connectTimeoutMs = Math.min(requestTimeoutMs, DEFAULT_CONNECT_TIMEOUT_MS);

        Exception lastException = null;
        for (int attempt = 1; attempt <= INITIALIZE_MAX_ATTEMPTS; attempt++) {
            McpSyncClient mcpSyncClient = null;
            try {
                HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport
                        .builder(baseUri)
                        .sseEndpoint(sseEndpoint)
                        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                        .build();

                mcpSyncClient = McpClient
                        .sync(sseClientTransport)
                        .requestTimeout(Duration.ofMillis(requestTimeoutMs)).build();

                McpSchema.InitializeResult initialize = mcpSyncClient.initialize();
                log.info("tool sse mcp initialize {}", initialize);

                return SyncMcpToolCallbackProvider.builder()
                        .mcpClients(mcpSyncClient).build()
                        .getToolCallbacks();
            } catch (Exception e) {
                lastException = e;
                closeQuietly(mcpSyncClient);
                if (attempt < INITIALIZE_MAX_ATTEMPTS) {
                    log.warn("tool sse mcp 初始化失败，将重试。name: {}, baseUri: {}, sseEndpoint: {}, attempt: {}/{}, retryIntervalMs: {}, 错误: {}",
                            sseConfig.getName(), baseUri, sseEndpoint, attempt, INITIALIZE_MAX_ATTEMPTS, INITIALIZE_RETRY_INTERVAL_MS, e.getMessage());
                    sleepBeforeRetry();
                }
            }
        }

        log.error("tool sse mcp 初始化失败，跳过该 MCP 服务。name: {}, baseUri: {}, sseEndpoint: {}, attempts: {}, 错误: {}",
                sseConfig.getName(), baseUri, sseEndpoint, INITIALIZE_MAX_ATTEMPTS,
                lastException == null ? "" : lastException.getMessage());
        return new ToolCallback[0];
    }

    private void closeQuietly(McpSyncClient mcpSyncClient) {
        if (mcpSyncClient == null) {
            return;
        }
        try {
            mcpSyncClient.close();
        } catch (Exception ignored) {
            // 关闭失败不影响主流程
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(INITIALIZE_RETRY_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
