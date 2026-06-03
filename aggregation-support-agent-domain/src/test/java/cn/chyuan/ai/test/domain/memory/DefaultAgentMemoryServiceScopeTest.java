package cn.chyuan.ai.test.domain.memory;

import cn.chyuan.ai.domain.memory.adapter.port.IMemoryConsolidationGateway;
import cn.chyuan.ai.domain.memory.adapter.port.IMemoryExtractionGateway;
import cn.chyuan.ai.domain.memory.adapter.repository.IAgentMemoryRepository;
import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.ExtractedFact;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryOptions;
import cn.chyuan.ai.domain.memory.model.valobj.RecallOptions;
import cn.chyuan.ai.domain.memory.service.impl.DefaultAgentMemoryService;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 记忆会话作用域测试")
class DefaultAgentMemoryServiceScopeTest {

    private final IMemoryExtractionGateway extractionGateway = mock(IMemoryExtractionGateway.class);
    private final IMemoryConsolidationGateway consolidationGateway = mock(IMemoryConsolidationGateway.class);
    private final IEmbeddingService embeddingService = mock(IEmbeddingService.class);
    private final IAgentMemoryRepository memoryRepository = mock(IAgentMemoryRepository.class);
    private final DefaultAgentMemoryService memoryService = new DefaultAgentMemoryService(
            extractionGateway,
            consolidationGateway,
            embeddingService,
            memoryRepository
    );

    @Test
    @DisplayName("召回短期记忆时限定当前会话作用域")
    void shouldRecallWithinConversationScope() {
        float[] queryEmbedding = new float[]{1.0f, 0.0f};
        when(embeddingService.embed("当前问题")).thenReturn(queryEmbedding);
        when(memoryRepository.search(
                eq(queryEmbedding),
                eq("tenant-a"),
                eq("user-a"),
                eq("/conversation/session-a"),
                eq(15)
        )).thenReturn(List.of());

        memoryService.recall("当前问题", RecallOptions.builder()
                .tenantId("tenant-a")
                .userId("user-a")
                .agentId("agent-a")
                .scope("/conversation/session-a")
                .limit(5)
                .build());

        verify(memoryRepository).search(
                eq(queryEmbedding),
                eq("tenant-a"),
                eq("user-a"),
                eq("/conversation/session-a"),
                eq(15)
        );
    }

    @Test
    @DisplayName("存储短期记忆时按当前会话作用域去重和相似检索")
    void shouldDeduplicateWithinConversationScope() {
        when(memoryRepository.existsByContentHash(anyString(), eq("tenant-a"), eq("user-a"), eq("/conversation/session-a")))
                .thenReturn(false);
        when(extractionGateway.extractFacts("用户: A\n助手: B")).thenReturn(List.of(
                ExtractedFact.builder()
                        .content("A 和 B 的对话事实")
                        .type(MemoryType.EPISODE)
                        .build()
        ));
        when(memoryRepository.searchSimilar(
                eq("A 和 B 的对话事实"),
                eq("tenant-a"),
                eq("user-a"),
                eq("/conversation/session-a"),
                eq(5)
        )).thenReturn(List.of());
        when(extractionGateway.assessImportance("A 和 B 的对话事实")).thenReturn(0.5f);
        when(embeddingService.embed("A 和 B 的对话事实")).thenReturn(new float[]{0.5f, 0.5f});

        memoryService.remember("用户: A\n助手: B", MemoryOptions.builder()
                .tenantId("tenant-a")
                .userId("user-a")
                .agentId("agent-a")
                .sessionId("session-a")
                .memoryType(MemoryType.EPISODE)
                .scope("/conversation/session-a")
                .source("chat")
                .build());

        verify(memoryRepository).existsByContentHash(anyString(), eq("tenant-a"), eq("user-a"), eq("/conversation/session-a"));
        verify(memoryRepository).searchSimilar(
                eq("A 和 B 的对话事实"),
                eq("tenant-a"),
                eq("user-a"),
                eq("/conversation/session-a"),
                eq(5)
        );
    }
}
