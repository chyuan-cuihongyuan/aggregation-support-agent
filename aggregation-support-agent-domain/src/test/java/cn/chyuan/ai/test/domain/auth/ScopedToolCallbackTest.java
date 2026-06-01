package cn.chyuan.ai.test.domain.auth;

import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.ScopedToolCallback;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.rag.model.valobj.RagSourceVO;
import cn.chyuan.ai.domain.rag.support.RagSourceCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Spring AI 工具租户上下文包装测试")
class ScopedToolCallbackTest {

    @AfterEach
    void tearDown() {
        RequestScopeContext.clear();
        RagSourceCollector.detach();
    }

    @Test
    @DisplayName("工具执行时从 ToolContext 恢复租户作用域和 RAG Holder")
    void shouldRestoreScopeAndHolderFromToolContext() {
        TenantScopeVO scope = TenantScopeVO.builder()
                .tenantId("tenant-a")
                .ownerUserId("user-a")
                .build();
        RagSourceCollector.begin();
        RagSourceCollector.Holder holder = RagSourceCollector.currentHolder();
        RagSourceCollector.detach();

        AtomicReference<TenantScopeVO> actualScope = new AtomicReference<>();
        ToolCallback callback = ScopedToolCallback.wrap(new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("test_tool")
                        .description("test")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "unused";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                actualScope.set(RequestScopeContext.snapshot());
                RagSourceCollector.append(List.of(RagSourceVO.builder().documentId("doc-1").build()));
                return "ok";
            }
        });

        String result = callback.call("{}", new ToolContext(Map.of(
                RequestScopeContext.TOOL_CONTEXT_TENANT_SCOPE_KEY, scope,
                RagSourceCollector.TOOL_CONTEXT_HOLDER_KEY, holder
        )));

        assertEquals("ok", result);
        assertEquals("tenant-a", actualScope.get().getTenantId());
        assertEquals("user-a", actualScope.get().getOwnerUserId());
        assertEquals(1, RagSourceCollector.drainHolder(holder).size());
        assertNull(RequestScopeContext.get());
        assertNull(RagSourceCollector.currentHolder());
    }
}
