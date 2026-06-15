package cn.chyuan.ai.domain.agent.service.chat;

import cn.chyuan.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import cn.chyuan.ai.domain.agent.model.entity.ChatSessionEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryOptions;
import cn.chyuan.ai.domain.memory.model.valobj.RecallOptions;
import cn.chyuan.ai.domain.memory.service.AgentMemoryService;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.SessionService;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private DefaultArmoryFactory defaultArmoryFactory;

    @Mock
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Mock
    private IChatHistoryRepository chatHistoryRepository;

    @Mock
    private AgentMemoryService agentMemoryService;

    @Mock
    private IEmbeddingService embeddingService;

    @Mock
    private InMemoryRunner runner;

    @Mock
    private SessionService sessionService;

    @InjectMocks
    private ChatService chatService;

    private String agentId;
    private String userId;
    private TenantScopeVO scope;

    @BeforeEach
    void setUp() {
        agentId = "test-agent";
        userId = "test-user";
        scope = TenantScopeVO.builder()
                .tenantId("tenant-1")
                .ownerUserId(userId)
                .build();
    }

    @Test
    void testQueryAiAgentConfigList_Success() {
        // Given
        Map<String, AiAgentConfigTableVO> tables = new HashMap<>();
        AiAgentConfigTableVO configVO = new AiAgentConfigTableVO();
        AiAgentConfigTableVO.Agent agent = new AiAgentConfigTableVO.Agent();
        agent.setId(agentId);
        agent.setName("Test Agent");
        configVO.setAgent(agent);
        tables.put("table1", configVO);

        when(aiAgentAutoConfigProperties.getTables()).thenReturn(tables);

        // When
        List<AiAgentConfigTableVO.Agent> result = chatService.queryAiAgentConfigList();

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(agentId, result.get(0).getId());
        verify(aiAgentAutoConfigProperties).getTables();
    }

    @Test
    void testQueryAiAgentConfigList_EmptyTables() {
        // Given
        when(aiAgentAutoConfigProperties.getTables()).thenReturn(null);

        // When
        List<AiAgentConfigTableVO.Agent> result = chatService.queryAiAgentConfigList();

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testCreateSession_Success() {
        // Given
        AiAgentRegisterVO registerVO = mock(AiAgentRegisterVO.class);
        when(registerVO.getAppName()).thenReturn("test-app");
        when(registerVO.getRunner()).thenReturn(runner);
        when(defaultArmoryFactory.getAiAgentRegisterVO(agentId)).thenReturn(registerVO);
        when(runner.sessionService()).thenReturn(sessionService);

        Session session = mock(Session.class);
        when(session.id()).thenReturn("session-123");
        when(sessionService.createSession(anyString(), anyString())).thenReturn(Maybe.just(session));

        try (MockedStatic<RequestScopeContext> scopeContext = mockStatic(RequestScopeContext.class)) {
            scopeContext.when(RequestScopeContext::snapshot).thenReturn(scope);

            // When
            String sessionId = chatService.createSession(agentId, userId);

            // Then
            assertNotNull(sessionId);
            assertEquals("session-123", sessionId);
            verify(chatHistoryRepository).saveSession(any(ChatSessionEntity.class));
        }
    }

    @Test
    void testCreateSession_AgentNotFound() {
        // Given
        when(defaultArmoryFactory.getAiAgentRegisterVO(agentId)).thenReturn(null);

        // When & Then
        AppException exception = assertThrows(AppException.class, () -> {
            chatService.createSession(agentId, userId);
        });

        assertEquals(ResponseCode.E0001.getCode(), exception.getCode());
    }

    @Test
    void testCreateSession_BlankAgentId() {
        // When & Then
        AppException exception = assertThrows(AppException.class, () -> {
            chatService.createSession("", userId);
        });

        assertEquals(ResponseCode.E0001.getCode(), exception.getCode());
    }

    @Test
    void testIsUserVisible_NoOutputAuthor() {
        // Given
        AiAgentRegisterVO registerVO = mock(AiAgentRegisterVO.class);
        when(registerVO.getOutputAuthor()).thenReturn(null);
        when(defaultArmoryFactory.getAiAgentRegisterVO(agentId)).thenReturn(registerVO);

        com.google.adk.events.Event event = mock(com.google.adk.events.Event.class);
        when(event.author()).thenReturn("any-agent");

        // When
        boolean result = chatService.isUserVisible(event, agentId);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsUserVisible_MatchingAuthor() {
        // Given
        AiAgentRegisterVO registerVO = mock(AiAgentRegisterVO.class);
        when(registerVO.getOutputAuthor()).thenReturn("terminal-agent");
        when(defaultArmoryFactory.getAiAgentRegisterVO(agentId)).thenReturn(registerVO);

        com.google.adk.events.Event event = mock(com.google.adk.events.Event.class);
        when(event.author()).thenReturn("terminal-agent");

        // When
        boolean result = chatService.isUserVisible(event, agentId);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsUserVisible_MultipleAuthors() {
        // Given
        AiAgentRegisterVO registerVO = mock(AiAgentRegisterVO.class);
        when(registerVO.getOutputAuthor()).thenReturn("agent1,agent2,agent3");
        when(defaultArmoryFactory.getAiAgentRegisterVO(agentId)).thenReturn(registerVO);

        com.google.adk.events.Event event = mock(com.google.adk.events.Event.class);
        when(event.author()).thenReturn("agent2");

        // When
        boolean result = chatService.isUserVisible(event, agentId);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsUserVisible_NonMatchingAuthor() {
        // Given
        AiAgentRegisterVO registerVO = mock(AiAgentRegisterVO.class);
        when(registerVO.getOutputAuthor()).thenReturn("terminal-agent");
        when(defaultArmoryFactory.getAiAgentRegisterVO(agentId)).thenReturn(registerVO);

        com.google.adk.events.Event event = mock(com.google.adk.events.Event.class);
        when(event.author()).thenReturn("intermediate-agent");

        // When
        boolean result = chatService.isUserVisible(event, agentId);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsUserVisible_NullAuthor() {
        // Given
        AiAgentRegisterVO registerVO = mock(AiAgentRegisterVO.class);
        when(registerVO.getOutputAuthor()).thenReturn("terminal-agent");
        when(defaultArmoryFactory.getAiAgentRegisterVO(agentId)).thenReturn(registerVO);

        com.google.adk.events.Event event = mock(com.google.adk.events.Event.class);
        when(event.author()).thenReturn(null);

        // When
        boolean result = chatService.isUserVisible(event, agentId);

        // Then
        assertFalse(result);
    }

    @Test
    void testStoreStreamConversationMemory_EmptyResponse() {
        // When
        chatService.storeStreamConversationMemory(userId, agentId, "session-1", "message", "");

        // Then
        verify(agentMemoryService, never()).remember(anyString(), any(MemoryOptions.class));
    }

    @Test
    void testStoreStreamConversationMemory_NullResponse() {
        // When
        chatService.storeStreamConversationMemory(userId, agentId, "session-1", "message", null);

        // Then
        verify(agentMemoryService, never()).remember(anyString(), any(MemoryOptions.class));
    }

    @Test
    void testStoreStreamConversationMemory_ValidResponse() {
        // Given
        String response = "Test response";
        try (MockedStatic<RequestScopeContext> scopeContext = mockStatic(RequestScopeContext.class)) {
            scopeContext.when(RequestScopeContext::snapshot).thenReturn(scope);

            // When
            chatService.storeStreamConversationMemory(userId, agentId, "session-1", "message", response);

            // Then
            verify(agentMemoryService, times(2)).remember(anyString(), any(MemoryOptions.class));
        }
    }
}
