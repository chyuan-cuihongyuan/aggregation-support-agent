package cn.chyuan.ai.test.domain.agent;

import cn.chyuan.ai.domain.agent.service.armory.matter.patch.MySpringAI;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.rag.support.RagSourceCollector;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MySpringAI 工具合并路径回归测试。
 * <p>
 * 验证删除 {@code ensureToolCallbacks} 和 {@code withScopedToolContext} 补丁后：
 * <ul>
 *   <li>{@code MySpringAI} 不重建 prompt options，不手动注入 toolCallbacks，
 *       信任 Spring AI {@code OpenAiChatModel.buildRequestPrompt()} 的原生合并；</li>
 *   <li>租户作用域（tenantScope）和 RAG Holder 通过 ThreadLocal（RequestScopeContext /
 *       RagSourceCollector）传递，{@code ScopedToolCallback} 从 ThreadLocal 兜底恢复；</li>
 *   <li>有/无工具场景下，{@code chatModel.call(prompt)} 都能被正常调用。</li>
 * </ul>
 *
 * @author chyuan
 */
@DisplayName("MySpringAI 工具合并路径回归测试")
class MySpringAIToolMergeTest {

    private static final String TENANT_SCOPE_KEY = RequestScopeContext.TOOL_CONTEXT_TENANT_SCOPE_KEY;
    private static final String HOLDER_KEY = RagSourceCollector.TOOL_CONTEXT_HOLDER_KEY;

    @BeforeEach
    void setUp() {
        RequestScopeContext.clear();
        RagSourceCollector.detach();
    }

    @AfterEach
    void tearDown() {
        RequestScopeContext.clear();
        RagSourceCollector.detach();
    }

    /**
     * 构造一个最小 LlmRequest：单条 user 消息，无 tools（模拟 superOrchestrator 未声明 .tools()）。
     */
    private LlmRequest buildLlmRequestWithoutTools() {
        return LlmRequest.builder()
                .model("test-model")
                .contents(List.of(
                        Content.fromParts(
                                com.google.genai.types.Part.fromText("查询订单 OD012026061617492689966"))))
                .build();
    }

    /**
     * 构造一个含 toolCallbacks 的 OpenAiChatModel mock：
     * stub getDefaultOptions() 返回带 agent_order_query 的 options，
     * stub call() 捕获入参 Prompt 并返回最小 ChatResponse。
     */
    private OpenAiChatModel mockChatModelWithTools(List<ToolCallback> toolCallbacks,
                                                   AtomicReference<Prompt> capturedPrompt) {
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model("test-model")
                .toolCallbacks(toolCallbacks)
                .build();

        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.getDefaultOptions()).thenReturn(defaultOptions);

        // 捕获传入 call() 的 prompt，返回一个最小响应（空 generation）
        doAnswer(invocation -> {
            capturedPrompt.set(invocation.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(""))));
        }).when(chatModel).call(any(Prompt.class));

        return chatModel;
    }

    private ToolCallback toolCallback(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(
                ToolDefinition.builder().name(name).description("desc").inputSchema("{}").build());
        return cb;
    }

    @Test
    @DisplayName("有工具 + 有租户作用域：MySpringAI 不重建 options，由 buildRequestPrompt 合并；作用域通过 ThreadLocal 传递")
    void shouldMergeTenantScopeIntoToolContextWithoutInjectingToolCallbacks() {
        // given：ChatModel 注册了 agent_order_query 等工具
        List<ToolCallback> defaultCallbacks = new ArrayList<>(List.of(
                toolCallback("agent_order_query"),
                toolCallback("agent_order_status")));
        AtomicReference<Prompt> captured = new AtomicReference<>();
        OpenAiChatModel chatModel = mockChatModelWithTools(defaultCallbacks, captured);

        MySpringAI springAI = new MySpringAI(chatModel, true);

        // attach 租户作用域（通过 ThreadLocal 传递，不再注入 prompt options）
        TenantScopeVO scope = TenantScopeVO.builder()
                .tenantId("tenant-a").ownerUserId("user-a").build();
        RequestScopeContext.attach(scope);
        RagSourceCollector.begin();
        RagSourceCollector.Holder holder = RagSourceCollector.currentHolder();

        // when：同步路径调用（stream=false）
        LlmRequest llmRequest = buildLlmRequestWithoutTools();
        springAI.generateContent(llmRequest, false).blockingSubscribe();

        // then：call 被调用，prompt 捕获到
        Prompt prompt = captured.get();
        assertNotNull(prompt, "chatModel.call() 应被调用并捕获 prompt");

        // ★核心回归：MySpringAI 不重建 prompt options★
        // prompt.getOptions() 可以为 null —— 实际运行时 Spring AI 的 buildRequestPrompt
        // 会合并 defaultOptions（含 agent_order_query 等 toolCallbacks）。
        // 重建 options 会破坏 mergeToolCallbacks 逻辑（runtime 非空时丢弃 default），
        // 导致 "No ToolCallback found for tool name: agent_order_query"。
        ChatOptions options = prompt.getOptions();
        if (options instanceof ToolCallingChatOptions toolOptions) {
            // 如果有 options，toolCallbacks 不应包含 agent_order_query（不应手动注入）
            List<ToolCallback> injected = toolOptions.getToolCallbacks();
            boolean orderQueryInjected = injected == null || injected.stream()
                    .noneMatch(cb -> "agent_order_query".equals(cb.getToolDefinition().name()));
            assertTrue(orderQueryInjected,
                    "MySpringAI 不应在 prompt 层注入 toolCallbacks（由 buildRequestPrompt 合并）");
        }

        // 租户作用域通过 ThreadLocal 传递（RequestScopeContext/RagSourceCollector），
        // ScopedToolCallback.call 会从 ThreadLocal 兜底恢复，不再依赖 prompt options 的 toolContext
        assertEquals(scope, RequestScopeContext.snapshot(),
                "租户作用域应仍存在于 ThreadLocal 中");
        assertNotNull(RagSourceCollector.currentHolder(),
                "RAG Holder 应仍存在于 ThreadLocal 中");
    }

    @Test
    @DisplayName("有工具 + 无租户作用域：prompt 原样传入，不崩溃")
    void shouldCallChatModelWithOriginalPromptWhenNoScope() {
        List<ToolCallback> defaultCallbacks = new ArrayList<>(List.of(
                toolCallback("agent_order_query")));
        AtomicReference<Prompt> captured = new AtomicReference<>();
        OpenAiChatModel chatModel = mockChatModelWithTools(defaultCallbacks, captured);

        MySpringAI springAI = new MySpringAI(chatModel, true);

        // 无 tenantScope / holder
        assertNull(RequestScopeContext.get());
        assertNull(RagSourceCollector.currentHolder());

        LlmRequest llmRequest = buildLlmRequestWithoutTools();
        springAI.generateContent(llmRequest, false).blockingSubscribe();

        assertNotNull(captured.get(), "chatModel.call() 应被调用");
    }

    @Test
    @DisplayName("无工具的纯对话智能体：正常调用，不抛异常")
    void shouldCallChatModelWhenNoToolsConfigured() {
        // ChatModel 无 toolCallbacks
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model("test-model").build();
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.getDefaultOptions()).thenReturn(defaultOptions);
        AtomicReference<Prompt> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }).when(chatModel).call(any(Prompt.class));

        MySpringAI springAI = new MySpringAI(chatModel, false);

        LlmRequest llmRequest = buildLlmRequestWithoutTools();
        springAI.generateContent(llmRequest, false).blockingSubscribe();

        assertNotNull(captured.get(), "纯对话智能体也应调用 chatModel.call()");
    }

    @Test
    @DisplayName("ChatModel.defaultOptions 含 agent_order_query：hasDefaultToolCallbacks 判定为 true（间接验证）")
    void shouldDetectDefaultToolCallbacks() {
        List<ToolCallback> defaultCallbacks = new ArrayList<>(List.of(
                toolCallback("agent_order_query"),
                toolCallback("queryInternalDocs")));
        AtomicReference<Prompt> captured = new AtomicReference<>();
        OpenAiChatModel chatModel = mockChatModelWithTools(defaultCallbacks, captured);

        MySpringAI springAI = new MySpringAI(chatModel, true);

        // 触发同步路径（stream=true 时会走 hasDefaultToolCallbacks 分支判断）
        LlmRequest llmRequest = buildLlmRequestWithoutTools();
        // stream=true + 有工具 → 走 generateContent（同步路径）
        springAI.generateContent(llmRequest, true).blockingSubscribe();

        assertNotNull(captured.get(), "有工具时流式入口应走同步路径并调用 call()");
    }

    @Test
    @DisplayName("回归保障：删除 ensureToolCallbacks 后，传给 call 的 prompt 仍含完整 instructions")
    void shouldPreserveInstructionsAfterRemovingEnsureToolCallbacks() {
        List<ToolCallback> defaultCallbacks = new ArrayList<>(List.of(
                toolCallback("agent_order_query")));
        AtomicReference<Prompt> captured = new AtomicReference<>();
        OpenAiChatModel chatModel = mockChatModelWithTools(defaultCallbacks, captured);

        MySpringAI springAI = new MySpringAI(chatModel, true);

        LlmRequest llmRequest = buildLlmRequestWithoutTools();
        springAI.generateContent(llmRequest, false).blockingSubscribe();

        Prompt prompt = captured.get();
        assertNotNull(prompt);
        assertFalse(prompt.getInstructions().isEmpty(),
                "prompt 的 instructions 不应为空");
        // user 消息内容应保留
        assertTrue(prompt.getInstructions().stream()
                        .anyMatch(m -> m.getText() != null && m.getText().contains("OD012026061617492689966")),
                "user 消息内容（订单号）应被保留在 prompt 中");
    }
}
