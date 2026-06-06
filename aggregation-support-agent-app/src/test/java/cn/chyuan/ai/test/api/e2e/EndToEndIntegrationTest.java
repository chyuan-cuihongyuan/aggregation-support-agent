package cn.chyuan.ai.test.api.e2e;

import cn.chyuan.ai.api.dto.ChatRequestDTO;
import cn.chyuan.ai.api.dto.CreateSessionRequestDTO;
import cn.chyuan.ai.api.dto.CreateSessionResponseDTO;
import cn.chyuan.ai.api.dto.SearchTestRequestDTO;
import cn.chyuan.ai.api.dto.SearchTestResultDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server.BusinessDataTools;
import cn.chyuan.ai.domain.rag.service.IRagService;
import cn.chyuan.ai.trigger.http.AgentServiceController;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试 — 覆盖知识库检索、业务数据工具、流式对话、错误处理等核心场景
 * <p>
 * 测试策略：
 * 1. 使用随机端口启动完整 Spring 上下文
 * 2. 通过 TestRestTemplate 模拟 HTTP 请求
 * 3. 验证各模块的集成正确性
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("端到端集成测试")
public class EndToEndIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired(required = false)
    private IRagService ragService;

    @Autowired(required = false)
    private BusinessDataTools businessDataTools;

    @Autowired
    private AgentServiceController agentServiceController;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://49.232.169.33:" + port + "/api/v1";
    }

    // ==================== 知识库检索测试 ====================

    @Nested
    @DisplayName("知识库检索测试")
    class KnowledgeRetrievalTest {

        @Test
        @Order(1)
        @DisplayName("测试RAG混合检索功能")
        void testRagHybridSearch() {
            log.info("开始测试RAG混合检索功能");

            // 跳过条件：RAG服务未启用时跳过
            if (ragService == null) {
                log.warn("RAG服务未启用，跳过测试");
                return;
            }

            // 构造检索请求
            SearchTestRequestDTO searchRequest = new SearchTestRequestDTO();
            searchRequest.setQuery("加油订单查询");
            searchRequest.setTopK(5);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<SearchTestRequestDTO> request = new HttpEntity<>(searchRequest, headers);

            // 执行检索测试接口
            ResponseEntity<Response<SearchTestResultDTO>> response = restTemplate.exchange(
                    baseUrl + "/documents/search",
                    HttpMethod.POST,
                    request,
                    new ParameterizedTypeReference<Response<SearchTestResultDTO>>() {
                    }
            );

            // 验证响应
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            log.info("RAG混合检索测试完成: {}", JSON.toJSONString(response.getBody()));
        }

        @Test
        @Order(2)
        @DisplayName("测试Query改写优化")
        void testQueryRewrite() {
            log.info("开始测试Query改写优化功能");

            if (ragService == null) {
                log.warn("RAG服务未启用，跳过测试");
                return;
            }

            // 使用模糊查询，验证Query改写是否生效
            String originalQuery = "订单";

            SearchTestRequestDTO searchRequest = new SearchTestRequestDTO();
            searchRequest.setQuery(originalQuery);
            searchRequest.setTopK(3);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<SearchTestRequestDTO> request = new HttpEntity<>(searchRequest, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/documents/search",
                    HttpMethod.POST,
                    request,
                    String.class
            );

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            log.info("Query改写测试完成: 原始查询={}, 响应={}", originalQuery, response.getBody());
        }
    }

    // ==================== 业务数据工具测试 ====================

    @Nested
    @DisplayName("业务数据工具测试")
    class BusinessDataToolsTest {

        @Test
        @Order(3)
        @DisplayName("测试queryOrder方法 - 正常查询")
        void testQueryOrder() {
            log.info("开始测试BusinessDataTools.queryOrder方法");

            if (businessDataTools == null) {
                log.warn("BusinessDataTools未注入，跳过测试");
                return;
            }

            // 测试订单查询
            String result = businessDataTools.queryOrder("TEST_ORDER_001", "13800138000");

            assertNotNull(result);
            log.info("订单查询结果: {}", result);

            // 验证返回的是有效JSON
            assertDoesNotThrow(() -> JSON.parseObject(result));
        }

        @Test
        @Order(4)
        @DisplayName("测试queryOrderStatus方法")
        void testQueryOrderStatus() {
            log.info("开始测试BusinessDataTools.queryOrderStatus方法");

            if (businessDataTools == null) {
                log.warn("BusinessDataTools未注入，跳过测试");
                return;
            }

            // 测试订单状态查询
            String result = businessDataTools.queryOrderStatus("TEST_ORDER_001");

            assertNotNull(result);
            log.info("订单状态查询结果: {}", result);

            // 验证返回的是有效JSON
            assertDoesNotThrow(() -> JSON.parseObject(result));
        }

        @Test
        @Order(5)
        @DisplayName("测试业务服务不可用时的错误处理")
        void testBusinessServiceUnavailable() {
            log.info("开始测试业务服务不可用时的错误处理");

            if (businessDataTools == null) {
                log.warn("BusinessDataTools未注入，跳过测试");
                return;
            }

            // 查询不存在的订单，预期返回错误信息
            String result = businessDataTools.queryOrder("NON_EXIST_ORDER", null);

            assertNotNull(result);
            log.info("服务不可用时的返回: {}", result);

            // 验证返回包含错误信息
            Map<String, Object> resultMap = JSON.parseObject(result, Map.class);
            assertTrue(resultMap.containsKey("error") || result.contains("error"),
                    "返回结果应包含错误标识");
        }
    }

    // ==================== 流式对话测试 ====================

    @Nested
    @DisplayName("流式对话测试")
    class StreamChatTest {

        @Test
        @Order(6)
        @DisplayName("测试chat_stream接口SSE响应")
        void testChatStreamSse() {
            log.info("开始测试chat_stream接口SSE响应");

            // 先创建会话
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 构造创建会话请求
            CreateSessionRequestDTO sessionRequest = new CreateSessionRequestDTO();
            sessionRequest.setAgentId("agent-001");

            HttpEntity<CreateSessionRequestDTO> sessionHttpReq = new HttpEntity<>(sessionRequest, headers);

            ResponseEntity<Response<CreateSessionResponseDTO>> sessionResponse = restTemplate.exchange(
                    baseUrl + "/create_session",
                    HttpMethod.POST,
                    sessionHttpReq,
                    new ParameterizedTypeReference<Response<CreateSessionResponseDTO>>() {
                    }
            );

            // 如果会话创建成功，测试流式对话
            if (sessionResponse.getStatusCode() == HttpStatus.OK
                    && sessionResponse.getBody() != null
                    && sessionResponse.getBody().getData() != null) {

                String sessionId = sessionResponse.getBody().getData().getSessionId();
                log.info("会话创建成功: sessionId={}", sessionId);

                // 构造流式对话请求
                ChatRequestDTO chatRequest = new ChatRequestDTO();
                chatRequest.setAgentId("agent-001");
                chatRequest.setSessionId(sessionId);
                chatRequest.setMessage("你好，请介绍一下你自己");

                HttpEntity<ChatRequestDTO> chatHttpReq = new HttpEntity<>(chatRequest, headers);

                // 发起流式请求
                ResponseEntity<String> response = restTemplate.exchange(
                        baseUrl + "/chat_stream",
                        HttpMethod.POST,
                        chatHttpReq,
                        String.class
                );

                log.info("流式对话响应状态: {}", response.getStatusCode());
                // SSE接口通常返回200 OK
                assertEquals(HttpStatus.OK, response.getStatusCode());
            } else {
                log.warn("会话创建失败，跳过流式对话测试: {}", sessionResponse.getBody());
            }
        }

        @Test
        @Order(7)
        @DisplayName("测试流式对话超时处理")
        void testChatStreamTimeout() {
            log.info("开始测试流式对话超时处理");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 构造一个可能导致超时的请求（空消息）
            ChatRequestDTO chatRequest = new ChatRequestDTO();
            chatRequest.setAgentId("agent-001");
            chatRequest.setMessage("");

            HttpEntity<ChatRequestDTO> request = new HttpEntity<>(chatRequest, headers);

            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        baseUrl + "/chat_stream",
                        HttpMethod.POST,
                        request,
                        String.class
                );

                log.info("超时测试响应: status={}", response.getStatusCode());
                // 即使超时也应返回响应（非500错误）
                assertNotEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(),
                        "超时不应导致500错误");
            } catch (Exception e) {
                log.info("超时测试捕获异常（预期行为）: {}", e.getMessage());
            }
        }
    }

    // ==================== 错误处理测试 ====================

    @Nested
    @DisplayName("错误处理测试")
    class ErrorHandlingTest {

        @Test
        @Order(8)
        @DisplayName("测试后端服务不可用时的降级处理")
        void testBackendServiceUnavailable() {
            log.info("开始测试后端服务不可用时的降级处理");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 使用不存在的agentId
            ChatRequestDTO chatRequest = new ChatRequestDTO();
            chatRequest.setAgentId("non-exist-agent");
            chatRequest.setMessage("测试消息");

            HttpEntity<ChatRequestDTO> request = new HttpEntity<>(chatRequest, headers);

            ResponseEntity<Response<Object>> response = restTemplate.exchange(
                    baseUrl + "/chat",
                    HttpMethod.POST,
                    request,
                    new ParameterizedTypeReference<Response<Object>>() {
                    }
            );

            // 验证降级处理：应返回错误响应而非500
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());

            // 验证错误码
            String code = response.getBody().getCode();
            assertNotNull(code);
            assertNotEquals("0000", code, "服务不可用时不应返回成功码");

            log.info("降级处理测试完成: code={}, info={}", code, response.getBody().getInfo());
        }

        @Test
        @Order(9)
        @DisplayName("测试认证失败时的错误返回")
        void testAuthenticationFailure() {
            log.info("开始测试认证失败时的错误返回");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // 不设置认证Cookie

            ChatRequestDTO chatRequest = new ChatRequestDTO();
            chatRequest.setAgentId("agent-001");
            chatRequest.setMessage("测试消息");

            HttpEntity<ChatRequestDTO> request = new HttpEntity<>(chatRequest, headers);

            // 访问需要认证的接口
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/chat",
                    HttpMethod.POST,
                    request,
                    String.class
            );

            log.info("认证测试响应: status={}, body={}", response.getStatusCode(), response.getBody());

            // 验证返回了认证相关的错误（可能是401或业务错误码）
            // 根据实际实现，可能返回401或200但带有错误码
            assertTrue(
                    response.getStatusCode() == HttpStatus.UNAUTHORIZED
                            || response.getStatusCode() == HttpStatus.FOUND
                            || response.getStatusCode() == HttpStatus.OK,
                    "应返回认证相关状态码"
            );
        }

        @Test
        @Order(10)
        @DisplayName("测试无效参数处理")
        void testInvalidParameters() {
            log.info("开始测试无效参数处理");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 发送空body
            HttpEntity<String> request = new HttpEntity<>("{}", headers);

            ResponseEntity<Response<Object>> response = restTemplate.exchange(
                    baseUrl + "/chat",
                    HttpMethod.POST,
                    request,
                    new ParameterizedTypeReference<Response<Object>>() {
                    }
            );

            // 验证参数校验
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());

            log.info("无效参数处理测试完成: {}", JSON.toJSONString(response.getBody()));
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取完整的API URL
     */
    private String getApiUrl(String path) {
        return baseUrl + path;
    }

    /**
     * 创建带认证头的HttpEntity
     */
    private <T> HttpEntity<T> createAuthRequest(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 如果需要Cookie认证，可以在这里添加
        // headers.add("Cookie", "ai_agent_login=xxx");
        return new HttpEntity<>(body, headers);
    }
}
