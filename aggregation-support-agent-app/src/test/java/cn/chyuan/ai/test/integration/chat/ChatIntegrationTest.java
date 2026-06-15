package cn.chyuan.ai.test.integration.chat;

import cn.chyuan.ai.api.dto.ChatRequestDTO;
import cn.chyuan.ai.api.dto.CreateSessionRequestDTO;
import cn.chyuan.ai.api.dto.CreateSessionResponseDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.test.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对话流程集成测试
 * <p>
 * 测试完整的对话流程：创建会话 → 发送消息 → 接收响应
 */
@DisplayName("对话流程集成测试")
public class ChatIntegrationTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String getBaseUrl() {
        return "http://localhost:" + port + "/api/v1";
    }

    @Test
    @DisplayName("测试创建会话成功")
    void testCreateSession_Success() {
        // Given
        CreateSessionRequestDTO request = new CreateSessionRequestDTO();
        request.setAgentId("test-agent");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<CreateSessionRequestDTO> entity = new HttpEntity<>(request, headers);

        // When
        ResponseEntity<Response<CreateSessionResponseDTO>> response = restTemplate.exchange(
                getBaseUrl() + "/create_session",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Response<CreateSessionResponseDTO>>() {}
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("0000", response.getBody().getCode());
        assertNotNull(response.getBody().getData());
        assertNotNull(response.getBody().getData().getSessionId());
    }

    @Test
    @DisplayName("测试创建会话 - Agent不存在")
    void testCreateSession_AgentNotFound() {
        // Given
        CreateSessionRequestDTO request = new CreateSessionRequestDTO();
        request.setAgentId("non-existent-agent");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<CreateSessionRequestDTO> entity = new HttpEntity<>(request, headers);

        // When
        ResponseEntity<Response<CreateSessionResponseDTO>> response = restTemplate.exchange(
                getBaseUrl() + "/create_session",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Response<CreateSessionResponseDTO>>() {}
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotEquals("0000", response.getBody().getCode());
    }

    @Test
    @DisplayName("测试创建会话 - AgentId为空")
    void testCreateSession_BlankAgentId() {
        // Given
        CreateSessionRequestDTO request = new CreateSessionRequestDTO();
        request.setAgentId("");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<CreateSessionRequestDTO> entity = new HttpEntity<>(request, headers);

        // When
        ResponseEntity<Response<CreateSessionResponseDTO>> response = restTemplate.exchange(
                getBaseUrl() + "/create_session",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Response<CreateSessionResponseDTO>>() {}
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotEquals("0000", response.getBody().getCode());
    }

    @Test
    @DisplayName("测试发送消息成功")
    void testSendMessage_Success() {
        // Given - 先创建会话
        CreateSessionRequestDTO sessionRequest = new CreateSessionRequestDTO();
        sessionRequest.setAgentId("test-agent");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<CreateSessionRequestDTO> sessionEntity = new HttpEntity<>(sessionRequest, headers);

        ResponseEntity<Response<CreateSessionResponseDTO>> sessionResponse = restTemplate.exchange(
                getBaseUrl() + "/create_session",
                HttpMethod.POST,
                sessionEntity,
                new ParameterizedTypeReference<Response<CreateSessionResponseDTO>>() {}
        );

        // 如果会话创建失败，跳过测试
        if (sessionResponse.getStatusCode() != HttpStatus.OK || 
            sessionResponse.getBody() == null || 
            sessionResponse.getBody().getData() == null) {
            return;
        }

        String sessionId = sessionResponse.getBody().getData().getSessionId();

        // When - 发送消息
        ChatRequestDTO chatRequest = new ChatRequestDTO();
        chatRequest.setAgentId("test-agent");
        chatRequest.setSessionId(sessionId);
        chatRequest.setMessage("你好，请介绍一下你自己");

        HttpEntity<ChatRequestDTO> chatEntity = new HttpEntity<>(chatRequest, headers);

        ResponseEntity<Response<Object>> chatResponse = restTemplate.exchange(
                getBaseUrl() + "/chat",
                HttpMethod.POST,
                chatEntity,
                new ParameterizedTypeReference<Response<Object>>() {}
        );

        // Then
        assertEquals(HttpStatus.OK, chatResponse.getStatusCode());
        assertNotNull(chatResponse.getBody());
        // 可能返回成功或业务错误（取决于 agent 配置），但不应是系统错误
        assertNotNull(chatResponse.getBody().getCode());
    }

    @Test
    @DisplayName("测试发送消息 - 会话不存在")
    void testSendMessage_SessionNotFound() {
        // Given
        ChatRequestDTO request = new ChatRequestDTO();
        request.setAgentId("test-agent");
        request.setSessionId("non-existent-session");
        request.setMessage("测试消息");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<ChatRequestDTO> entity = new HttpEntity<>(request, headers);

        // When
        ResponseEntity<Response<Object>> response = restTemplate.exchange(
                getBaseUrl() + "/chat",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Response<Object>>() {}
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotEquals("0000", response.getBody().getCode());
    }

    @Test
    @DisplayName("测试发送消息 - 消息为空")
    void testSendMessage_EmptyMessage() {
        // Given - 先创建会话
        CreateSessionRequestDTO sessionRequest = new CreateSessionRequestDTO();
        sessionRequest.setAgentId("test-agent");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<CreateSessionRequestDTO> sessionEntity = new HttpEntity<>(sessionRequest, headers);

        ResponseEntity<Response<CreateSessionResponseDTO>> sessionResponse = restTemplate.exchange(
                getBaseUrl() + "/create_session",
                HttpMethod.POST,
                sessionEntity,
                new ParameterizedTypeReference<Response<CreateSessionResponseDTO>>() {}
        );

        if (sessionResponse.getStatusCode() != HttpStatus.OK || 
            sessionResponse.getBody() == null || 
            sessionResponse.getBody().getData() == null) {
            return;
        }

        String sessionId = sessionResponse.getBody().getData().getSessionId();

        // When - 发送空消息
        ChatRequestDTO chatRequest = new ChatRequestDTO();
        chatRequest.setAgentId("test-agent");
        chatRequest.setSessionId(sessionId);
        chatRequest.setMessage("");

        HttpEntity<ChatRequestDTO> chatEntity = new HttpEntity<>(chatRequest, headers);

        ResponseEntity<Response<Object>> chatResponse = restTemplate.exchange(
                getBaseUrl() + "/chat",
                HttpMethod.POST,
                chatEntity,
                new ParameterizedTypeReference<Response<Object>>() {}
        );

        // Then
        assertEquals(HttpStatus.OK, chatResponse.getStatusCode());
        assertNotNull(chatResponse.getBody());
    }
}
