package cn.chyuan.ai.domain.agent.service.armory.matter.patch;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.rag.support.RagSourceCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.MessageConverter;
import com.google.adk.models.springai.error.SpringAIErrorMapper;
import com.google.adk.models.springai.observability.SpringAIObservabilityHandler;
import com.google.adk.models.springai.properties.SpringAIProperties;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Spring AI 补丁
 * @author chyuan @chyuan
 * 2026/1/9 08:20
 */
@Slf4j
public class MySpringAI extends BaseLlm {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ObjectMapper objectMapper;
    private final MessageConverter messageConverter;
    private final SpringAIObservabilityHandler observabilityHandler;

    /** 标识该智能体是否配置了工具，有工具时走同步路径防止流式输出中自问自答 */
    private boolean hasConfiguredTools;

    public MySpringAI(ChatModel chatModel) {
        super(extractModelName(chatModel));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(ChatModel chatModel, boolean hasConfiguredTools) {
        this(chatModel);
        this.hasConfiguredTools = hasConfiguredTools;
    }

    public MySpringAI(ChatModel chatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(StreamingChatModel streamingChatModel) {
        super(extractModelName(streamingChatModel));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(StreamingChatModel streamingChatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(ChatModel chatModel, StreamingChatModel streamingChatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(
            ChatModel chatModel,
            StreamingChatModel streamingChatModel,
            String modelName,
            SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    public MySpringAI(
            ChatModel chatModel, String modelName, SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    public MySpringAI(
            StreamingChatModel streamingChatModel,
            String modelName,
            SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        if (stream) {
            if (this.streamingChatModel == null) {
                return Flowable.error(new IllegalStateException("StreamingChatModel is not configured"));
            }

            // 有工具配置的智能体走同步路径：chatModel.call() 一次性返回完整响应，
            // 模型在 stop token 处自然结束，避免流式模式下的自问自答持续输出
            if ((hasConfiguredTools || hasTools(llmRequest)) && this.chatModel != null) {
                return generateContent(llmRequest);
            }

            // 无工具的纯对话智能体走流式路径
            return generateStreamingContent(llmRequest);
        } else {
            if (this.chatModel == null) {
                return Flowable.error(new IllegalStateException("ChatModel is not configured"));
            }

            return generateContent(llmRequest);
        }
    }

    private Flowable<LlmResponse> generateContent(LlmRequest llmRequest) {
        SpringAIObservabilityHandler.RequestContext context =
                observabilityHandler.startRequest(model(), "chat");

        try {
            TenantScopeVO tenantScope = currentTenantScope();
            RagSourceCollector.Holder holder = RagSourceCollector.currentHolder();
            Prompt prompt = withScopedToolContext(messageConverter.toLlmPrompt(llmRequest), tenantScope, holder);
            observabilityHandler.logRequest(prompt.toString(), model());

            ChatResponse chatResponse = chatModel.call(prompt);
            LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);

            observabilityHandler.logResponse(extractTextFromResponse(llmResponse), model());

            // Extract token counts if available
            int totalTokens = extractTokenCount(chatResponse);
            int inputTokens = extractInputTokenCount(chatResponse);
            int outputTokens = extractOutputTokenCount(chatResponse);

            observabilityHandler.recordSuccess(context, totalTokens, inputTokens, outputTokens);

            // 新增：写入 Holder，供可观测性上报
            if (holder != null) {
                holder.setPromptTokens(inputTokens);
                holder.setCompletionTokens(outputTokens);
                holder.setModelVersion(model());
            }

            return Flowable.just(llmResponse);
        } catch (Exception e) {
            observabilityHandler.recordError(context, e);
            logNoToolCallbackDiagnostic(e);
            SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(e);

            return Flowable.error(new RuntimeException(mappedError.getNormalizedMessage(), e));
        }
    }

    private Flowable<LlmResponse> generateStreamingContent(LlmRequest llmRequest) {
        SpringAIObservabilityHandler.RequestContext context =
                observabilityHandler.startRequest(model(), "streaming");

        return Flowable.create(
                emitter -> {
                    try {
                        TenantScopeVO tenantScope = currentTenantScope();
                        RagSourceCollector.Holder holder = RagSourceCollector.currentHolder();
                        Prompt prompt = withScopedToolContext(messageConverter.toLlmPrompt(llmRequest), tenantScope, holder);
                        observabilityHandler.logRequest(prompt.toString(), model());

                        if (this.chatModel != null && hasDefaultToolCallbacks()) {
                            ChatResponse chatResponse = chatModel.call(prompt);
                            LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);
                            observabilityHandler.logResponse(extractTextFromResponse(llmResponse), model());
                            int totalTokens = extractTokenCount(chatResponse);
                            int inputTokens = extractInputTokenCount(chatResponse);
                            int outputTokens = extractOutputTokenCount(chatResponse);
                            observabilityHandler.recordSuccess(context, totalTokens, inputTokens, outputTokens);
                            // 写入 Holder，供可观测性上报（与非流式 generateContent 路径保持一致）
                            if (holder != null) {
                                holder.setPromptTokens(inputTokens);
                                holder.setCompletionTokens(outputTokens);
                                holder.setModelVersion(model());
                            }
                            emitter.onNext(llmResponse);
                            emitter.onComplete();
                            return;
                        }

                        Flux<ChatResponse> responseFlux = withThreadLocalScope(prompt, tenantScope, holder);

                        // 累计流式响应的 token 数（多数 provider 仅在最后一个 chunk 返回 usage，取最后非零值）
                        final int[] tokenAccumulator = new int[]{0, 0, 0};

                        responseFlux
                                .doOnError(
                                        error -> {
                                            observabilityHandler.recordError(context, error);
                                            SpringAIErrorMapper.MappedError mappedError =
                                                    SpringAIErrorMapper.mapError(error);
                                            emitter.onError(
                                                    new RuntimeException(mappedError.getNormalizedMessage(), error));
                                        })
                                .subscribe(
                                        chatResponse -> {
                                            try {
                                                // 累计 token（部分 provider 在中间 chunk 也返回 usage）
                                                int t = extractTokenCount(chatResponse);
                                                int i = extractInputTokenCount(chatResponse);
                                                int o = extractOutputTokenCount(chatResponse);
                                                if (t > 0) tokenAccumulator[0] = t;
                                                if (i > 0) tokenAccumulator[1] = i;
                                                if (o > 0) tokenAccumulator[2] = o;
                                                // Use enhanced streaming-aware conversion
                                                LlmResponse llmResponse =
                                                        messageConverter.toLlmResponse(chatResponse, true);
                                                if (isEmptyResponse(llmResponse)) {
                                                    return;
                                                }
                                                emitter.onNext(llmResponse);
                                            } catch (Exception e) {
                                                observabilityHandler.recordError(context, e);
                                                SpringAIErrorMapper.MappedError mappedError =
                                                        SpringAIErrorMapper.mapError(e);
                                                emitter.onError(
                                                        new RuntimeException(mappedError.getNormalizedMessage(), e));
                                            }
                                        },
                                        error -> {
                                            observabilityHandler.recordError(context, error);
                                            SpringAIErrorMapper.MappedError mappedError =
                                                    SpringAIErrorMapper.mapError(error);
                                            emitter.onError(
                                                    new RuntimeException(mappedError.getNormalizedMessage(), error));
                                        },
                                        () -> {
                                            // 写入 Holder，供可观测性上报（与非流式路径保持一致）
                                            if (holder != null) {
                                                holder.setPromptTokens(tokenAccumulator[1]);
                                                holder.setCompletionTokens(tokenAccumulator[2]);
                                                holder.setModelVersion(model());
                                            }
                                            observabilityHandler.recordSuccess(context, tokenAccumulator[0], tokenAccumulator[1], tokenAccumulator[2]);
                                            emitter.onComplete();
                                        });
                    } catch (Exception e) {
                        observabilityHandler.recordError(context, e);
                        logNoToolCallbackDiagnostic(e);
                        SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(e);
                        emitter.onError(new RuntimeException(mappedError.getNormalizedMessage(), e));
                    }
                },
                BackpressureStrategy.BUFFER);
    }

    private boolean hasDefaultToolCallbacks() {
        List<ToolCallback> defaultToolCallbacks = resolveDefaultToolCallbacks();
        return defaultToolCallbacks != null && !defaultToolCallbacks.isEmpty();
    }

    private boolean hasTools(LlmRequest llmRequest) {
        return llmRequest.tools() != null && !llmRequest.tools().isEmpty();
    }

    private boolean isEmptyResponse(LlmResponse llmResponse) {
        if (llmResponse == null || llmResponse.content().isEmpty()) {
            return true;
        }
        return llmResponse.content()
                .flatMap(content -> content.parts()
                        .map(parts -> parts.stream().noneMatch(part ->
                                part.text().map(text -> !text.isEmpty()).orElse(false)
                                        || part.functionCall().isPresent())))
                .orElse(true);
    }

    private Prompt withScopedToolContext(Prompt prompt, TenantScopeVO tenantScope, RagSourceCollector.Holder holder) {
        Map<String, Object> scopedContext = new LinkedHashMap<>();
        if (tenantScope != null) {
            scopedContext.put(RequestScopeContext.TOOL_CONTEXT_TENANT_SCOPE_KEY, tenantScope);
        }
        if (holder != null) {
            scopedContext.put(RagSourceCollector.TOOL_CONTEXT_HOLDER_KEY, holder);
        }
        if (scopedContext.isEmpty()) {
            return prompt;
        }

        ChatOptions options = prompt.getOptions();
        ChatOptions scopedOptions = mergeToolContext(options, scopedContext);
        return Prompt.builder()
                .messages(prompt.getInstructions())
                .chatOptions(scopedOptions)
                .build();
    }

    private TenantScopeVO currentTenantScope() {
        TenantScopeVO tenantScope = RequestScopeContext.snapshot();
        if (tenantScope == null) {
            tenantScope = RagSourceCollector.currentTenantScope();
        }
        return tenantScope;
    }

    private ChatOptions mergeToolContext(ChatOptions options, Map<String, Object> scopedContext) {
        if (options instanceof ToolCallingChatOptions toolOptions) {
            ChatOptions copiedOptions = toolOptions.copy();
            if (copiedOptions instanceof ToolCallingChatOptions copiedToolOptions) {
                Map<String, Object> merged = new LinkedHashMap<>();
                if (copiedToolOptions.getToolContext() != null) {
                    merged.putAll(copiedToolOptions.getToolContext());
                }
                merged.putAll(scopedContext);
                copiedToolOptions.setToolContext(merged);
                return copiedToolOptions;
            }
        }
        return ToolCallingChatOptions.builder()
                .toolContext(scopedContext)
                .build();
    }

    /**
     * Resolves the default toolCallbacks registered on the ChatModel's defaultOptions.
     * Returns null if the ChatModel is not an OpenAiChatModel or has no toolCallbacks.
     * <p>
     * Spring AI 的 {@code OpenAiChatModel.buildRequestPrompt()} 会自动把 defaultOptions
     * 的 toolCallbacks 合并进运行时 prompt options（由 {@code mergeToolCallbacks} 完成），
     * 因此本类无需在 prompt 层手动桥接 toolCallbacks —— 那样反而会因重建 options 对象、
     * 破坏 OpenAiChatOptions 类型而触发 "No ToolCallback found" 错误。
     */
    private List<ToolCallback> resolveDefaultToolCallbacks() {
        if (chatModel instanceof OpenAiChatModel openAiChatModel) {
            ChatOptions defaultOptions = openAiChatModel.getDefaultOptions();
            if (defaultOptions instanceof OpenAiChatOptions openAiOptions) {
                return openAiOptions.getToolCallbacks();
            }
        }
        return null;
    }

    /**
     * 当工具执行抛出 "No ToolCallback found for tool name: X" 类异常时，输出诊断信息：
     * 当前 ChatModel defaultOptions 上注册的工具名清单。帮助快速定位是工具未注册、
     * 还是合并路径异常。
     */
    private void logNoToolCallbackDiagnostic(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (!message.contains("No ToolCallback found")) {
            return;
        }
        List<ToolCallback> defaultToolCallbacks = resolveDefaultToolCallbacks();
        if (defaultToolCallbacks == null || defaultToolCallbacks.isEmpty()) {
            log.error("工具执行失败且 ChatModel.defaultOptions 无任何 toolCallbacks 注册。" +
                    "请检查 MCP/Skills 工具装配是否成功。异常: {}", message);
        } else {
            String registeredNames = defaultToolCallbacks.stream()
                    .map(cb -> cb.getToolDefinition().name())
                    .collect(Collectors.joining(", "));
            log.error("工具执行失败。ChatModel.defaultOptions 已注册 {} 个工具: [{}]。" +
                    "异常: {}", defaultToolCallbacks.size(), registeredNames, message);
        }
    }

    private Flux<ChatResponse> withThreadLocalScope(
            Prompt prompt,
            TenantScopeVO tenantScope,
            RagSourceCollector.Holder holder) {
        if (tenantScope == null && holder == null) {
            return streamingChatModel.stream(prompt);
        }
        return Flux.defer(() -> {
            TenantScopeVO previousScope = RequestScopeContext.snapshot();
            RagSourceCollector.Holder previousHolder = RagSourceCollector.currentHolder();
            if (tenantScope != null) {
                RequestScopeContext.attach(tenantScope);
            }
            if (holder != null) {
                RagSourceCollector.attach(holder);
            }
            return streamingChatModel.stream(prompt)
                    .doFinally(signalType -> {
                        RequestScopeContext.attach(previousScope);
                        RagSourceCollector.attach(previousHolder);
                    });
        });
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException(
                "Live connection is not supported for Spring AI models.");
    }

    private static String extractModelName(Object model) {
        // Spring AI models may not always have a straightforward way to get model name
        // This is a fallback that can be overridden by providing explicit model name
        String className = model.getClass().getSimpleName();
        return className.toLowerCase().replace("chatmodel", "").replace("model", "");
    }

    private SpringAIProperties.Observability createDefaultObservabilityConfig() {
        SpringAIProperties.Observability config = new SpringAIProperties.Observability();
        config.setEnabled(true);
        config.setMetricsEnabled(true);
        config.setIncludeContent(false);
        return config;
    }

    private int extractTokenCount(ChatResponse chatResponse) {
        // Spring AI may include usage metadata in the response
        // This is a simplified implementation - actual token counts depend on provider
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getTotalTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private int extractInputTokenCount(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getPromptTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private int extractOutputTokenCount(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getCompletionTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private String extractTextFromResponse(LlmResponse response) {
        if (response.content().isPresent() && response.content().get().parts().isPresent()) {
            return response.content().get().parts().get().stream()
                    .map(part -> part.text().orElse(""))
                    .filter(text -> text != null && !text.isEmpty())
                    .findFirst()
                    .orElse("");
        }
        return "";
    }
    
}
