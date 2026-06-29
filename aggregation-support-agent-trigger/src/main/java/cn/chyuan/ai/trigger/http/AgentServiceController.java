package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.IAgentService;
import cn.chyuan.ai.api.dto.*;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.service.IChatService;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.rag.model.valobj.RagSourceVO;
import cn.chyuan.ai.domain.rag.support.RagSourceCollector;
import cn.chyuan.ai.infrastructure.utils.ObservabilityHelper;
import cn.chyuan.ai.trigger.support.CurrentUserSupport;
import cn.chyuan.ai.trigger.support.TenantScopeSupport;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import com.google.adk.events.Event;
import org.springframework.http.MediaType;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 *
 * @author chyuan @chyuan
 * 2026/1/20 08:23
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

    @Resource
    private ObservabilityHelper observabilityHelper;

    /** 记忆存储线程池 — 复用全局 memoryTaskExecutor，替代裸 Thread，避免高并发下线程爆炸 */
    @Resource(name = "memoryTaskExecutor")
    private org.springframework.core.task.AsyncTaskExecutor memoryTaskExecutor;

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表");

            List<AiAgentConfigTableVO.Agent> agentConfigs = chatService.queryAiAgentConfigList();

            List<AiAgentConfigResponseDTO> responseDTOS = agentConfigs.stream().map(agentConfig -> {
                AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
                responseDTO.setAgentId(agentConfig.getAgentId());
                responseDTO.setAgentName(agentConfig.getAgentName());
                responseDTO.setAgentDesc(agentConfig.getAgentDesc());
                return responseDTO;
            }).collect(Collectors.toList());

            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();

        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    public Response<CreateSessionResponseDTO> createSession(HttpServletRequest request, @RequestBody CreateSessionRequestDTO requestDTO) {
        try {
            String userId = CurrentUserSupport.requireUserIdString(request);
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), userId);
            String sessionId = chatService.createSession(requestDTO.getAgentId(), userId);

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("创建会话异常", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(HttpServletRequest request, @RequestParam("agentId") String agentId) {
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId(agentId);
        return createSession(request, requestDTO);
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    public Response<ChatResponseDTO> chat(HttpServletRequest request, @RequestBody ChatRequestDTO requestDTO) {
        long start = System.currentTimeMillis();
        String userId = null;
        String sessionId = null;
        // 请求入口生成 traceId，作为本次对话所有上报的兜底标识（未触发 RAG 时检索级 traceId 为空）
        final String requestTraceId = generateRequestTraceId();
        try {
            userId = CurrentUserSupport.requireUserIdString(request);
            log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), userId);
            sessionId = resolveSessionId(requestDTO.getSessionId(), requestDTO.getAgentId(), userId);

            // 确保 ADK 内存会话有效（处理应用重启后 session 丢失场景）
            sessionId = chatService.ensureAdkSession(requestDTO.getAgentId(), userId, sessionId);

            List<String> messages;
            String traceId;
            RagSourceCollector.Holder holder;
            try {
                messages = chatService.handleMessage(requestDTO.getAgentId(), userId, sessionId, requestDTO.getMessage());
            } finally {
                // 出口统一取出 Holder 快照后 drain，确保异常路径也清理 ThreadLocal
                holder = RagSourceCollector.currentHolder();
                traceId = RagSourceCollector.getTraceId();
                RagSourceCollector.drain();
            }

            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setContent(String.join("\n", messages));
            // traceId 和 sources 仅用于内部可观测性上报，不再返回给前端

            // 上报 traceId：检索级为空（未触发 RAG）时回退到请求入口生成的 traceId
            String reportTraceId = resolveReportTraceId(traceId, requestTraceId);
            int costMs = (int) (System.currentTimeMillis() - start);
            reportObservability(holder, reportTraceId, sessionId, userId, requestDTO.getAgentId(),
                    requestDTO.getMessage(), responseDTO.getContent(), "SUCCESS", costMs, null);

            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常", e);
            reportChatFailure(requestTraceId, sessionId, userId, requestDTO.getAgentId(), requestDTO.getMessage(),
                    (int) (System.currentTimeMillis() - start), e.getInfo());
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            reportChatFailure(requestTraceId, sessionId, userId, requestDTO.getAgentId(), requestDTO.getMessage(),
                    (int) (System.currentTimeMillis() - start), e.getMessage());
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 统一上报一次对话的可观测性数据：问答结果 + RAG 检索 + Agent 决策。
     * holder 为空（未触发检索）时仍上报问答结果与决策，仅跳过检索上报。
     */
    private void reportObservability(RagSourceCollector.Holder holder, String traceId,
                                     String sessionId, String userId, String agentId,
                                     String question, String answer, String status,
                                     int costMs, String errorMessage) {
        try {
            // 从 Holder 中获取增强字段
            int promptTokens = holder != null ? holder.getPromptTokens() : 0;
            int completionTokens = holder != null ? holder.getCompletionTokens() : 0;
            String modelVersion = holder != null ? holder.getModelVersion() : null;
            String decisionReason = holder != null ? holder.getAgentThought() : null;
            List<Map<String, Object>> toolCalls = holder != null ? holder.getToolCalls() : Collections.emptyList();
            int toolCallTimes = toolCalls.size();
            int toolRetryTimes = holder != null ? holder.getToolRetryTimes() : 0;

            // 构建 selectedToolList JSON
            String selectedToolList = null;
            if (!toolCalls.isEmpty()) {
                selectedToolList = com.alibaba.fastjson.JSON.toJSONString(toolCalls.stream()
                    .map(tc -> Map.of("toolName", tc.get("toolName"), "callOrder", tc.get("callOrder")))
                    .collect(Collectors.toList()));
            }

            // 从 branchType 推导 intentType
            // 注意：branchType 依据检索级 traceId（holder 内部）判断，而非上报 traceId（已兜底非空）
            String branchType = (holder != null && !holder.getTraceId().isEmpty()) ? "RAG" : "DIRECT_ANSWER";
            // 如果有工具调用但无 RAG 检索，则修正为 TOOL_CALL
            if ("DIRECT_ANSWER".equals(branchType) && !toolCalls.isEmpty()) {
                branchType = "TOOL_CALL";
            }
            String intentType = deriveIntentType(branchType);

            // 从工具调用序列构建 planSteps（简化版：每个工具调用视为一个规划步骤）
            String planSteps = null;
            if (!toolCalls.isEmpty()) {
                planSteps = com.alibaba.fastjson.JSON.toJSONString(toolCalls.stream()
                    .map(tc -> Map.of("step", tc.get("toolName"), "order", tc.get("callOrder"), "status", tc.get("status")))
                    .collect(Collectors.toList()));
            }

            // 上报问答结果（含 Token 消耗）
            observabilityHelper.reportChatResult(traceId, sessionId, userId, agentId, question, answer,
                    promptTokens, completionTokens, status, costMs, modelVersion);

            // 上报 RAG 检索（含 retrievalStages）：仅检索级 traceId 非空（即触发了 RAG）时上报
            if (holder != null && !holder.getTraceId().isEmpty()) {
                observabilityHelper.reportRagRetrieval(traceId, sessionId, userId, agentId,
                        holder.getRetrievalQuery(), holder.getRewriteText(), holder.getTopK(),
                        holder.snapshotSources(), costMs,
                        holder.getRetrievalStages(), holder.getRagStrategyVersion());
            }

            // 上报 Agent 决策（含工具调用信息、意图类型、规划步骤）
            observabilityHelper.reportAgentDecision(traceId, sessionId, userId, null, agentId,
                    question, intentType, selectedToolList, decisionReason, branchType, planSteps,
                    toolCallTimes, toolRetryTimes, status, costMs, modelVersion, errorMessage);

            // 上报工具调用详情 + 记忆检索结果
            reportToolCallDetails(traceId, toolCalls);
            reportMemoryRecallDetails(traceId, holder);
        } catch (Exception e) {
            log.debug("observability report failed: {}", e.getMessage());
        }
    }

    /** 对话失败路径上报：无检索证据，仅上报问答结果(FAIL) + 决策 */
    private void reportChatFailure(String traceId, String sessionId, String userId, String agentId,
                                   String question, int costMs, String errorMessage) {
        try {
            observabilityHelper.reportChatResult(traceId, sessionId, userId, agentId, question, "", "FAIL", costMs);
            observabilityHelper.reportAgentDecision(traceId, sessionId, userId, null, agentId,
                    question, "DIRECT_ANSWER", "FAIL", costMs, errorMessage);
        } catch (Exception e) {
            log.debug("observability fail report failed: {}", e.getMessage());
        }
    }

    /**
     * 生成请求级 traceId：本次对话的唯一标识，用于串联问答/检索/决策/工具/记忆等所有上报。
     * 与 {@link RagSourceCollector#getTraceId()}（检索级，仅触发 RAG 时才写入）不同，
     * 本方法保证无论走哪个分支（直接回答 / 工具调用 / RAG 检索）都有 traceId。
     */
    private static String generateRequestTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 解析上报用 traceId：优先用检索级 traceId（命中 RAG 时已由 RagService 写入），
     * 为空（未触发检索）时回退到请求入口生成的 traceId，确保每条上报都有非空 traceId。
     */
    private static String resolveReportTraceId(String retrievalTraceId, String requestTraceId) {
        if (retrievalTraceId != null && !retrievalTraceId.isEmpty()) {
            return retrievalTraceId;
        }
        return requestTraceId == null ? "" : requestTraceId;
    }

    /**
     * 从分支类型推导意图类型
     */
    private String deriveIntentType(String branchType) {
        if (branchType == null) return "general_chat";
        return switch (branchType) {
            case "RAG" -> "knowledge_query";
            case "TOOL_CALL" -> "tool_invocation";
            case "REJECT" -> "rejection";
            default -> "general_chat";
        };
    }

    /**
     * 安全地从 Map 中获取 Integer 值，避免 ClassCastException
     * （JSON 反序列化可能将整数解析为 Long/Double 等类型）
     */
    private int safeGetInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultValue;
    }

    /**
     * 上报工具调用详情（安全获取整数值，防止 ClassCastException；traceId 为空时跳过）
     */
    private void reportToolCallDetails(String traceId, List<Map<String, Object>> toolCalls) {
        if (traceId == null || traceId.isEmpty() || toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        for (Map<String, Object> tc : toolCalls) {
            observabilityHelper.reportToolCall(
                traceId,
                traceId + "_" + safeGetInt(tc, "callOrder", 0),
                traceId,
                (String) tc.get("toolName"),
                tc.get("toolInput") != null ? tc.get("toolInput").toString() : null,
                tc.get("toolOutput") != null ? tc.get("toolOutput").toString() : null,
                (String) tc.get("status"),
                safeGetInt(tc, "costTimeMs", 0),
                (String) tc.get("errorMessage"),
                safeGetInt(tc, "callOrder", 0)
            );
        }
    }

    /**
     * 上报记忆检索结果（安全获取整数值，防止 ClassCastException；traceId 为空时跳过）
     */
    private void reportMemoryRecallDetails(String traceId, RagSourceCollector.Holder holder) {
        if (traceId == null || traceId.isEmpty() || holder == null || holder.getMemoryRecallResult() == null) {
            return;
        }
        Map<String, Object> mr = holder.getMemoryRecallResult();
        observabilityHelper.reportMemoryRecall(
            traceId,
            (String) mr.get("queryText"),
            safeGetInt(mr, "sessionMemoryCount", 0),
            safeGetInt(mr, "agentMemoryCount", 0),
            com.alibaba.fastjson.JSON.toJSONString(mr.get("sessionMemoryScores")),
            com.alibaba.fastjson.JSON.toJSONString(mr.get("agentMemoryScores")),
            null, // injectContent 不记录（可能过大）
            safeGetInt(mr, "costTimeMs", 0)
        );
    }

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(HttpServletRequest request, @RequestBody ChatRequestDTO requestDTO) {
        SseEmitter emitter = new SseEmitter(3 * 60 * 1000L);
        RagSourceCollector.Holder requestHolder = null;
        long start = System.currentTimeMillis();
        // 请求入口生成 traceId，作为本次流式对话所有上报的兜底标识（未触发 RAG 时检索级 traceId 为空）
        final String requestTraceId = generateRequestTraceId();
        try {
            String userId = CurrentUserSupport.requireUserIdString(request);
            String agentId = requestDTO.getAgentId();
            String sessionId = resolveSessionId(requestDTO.getSessionId(), agentId, userId);
            String message = requestDTO.getMessage();
            final String finalUserIdForReport = userId;
            final String finalSessionIdForReport = sessionId;
            final String finalAgentIdForReport = agentId;
            TenantScopeVO requestScope = TenantScopeSupport.currentScope(request);
            
            log.info("流式对话 agentId:{} userId:{} sessionId:{}", agentId, userId, sessionId);

            // 在发送 SSE session 事件前，确保 ADK 内存会话有效（处理应用重启后 session 丢失场景）
            final String effectiveSessionId = chatService.ensureAdkSession(agentId, userId, sessionId);

            emitter.send(SseEmitter.event().name("session").data(Collections.singletonMap("sessionId", effectiveSessionId)));

            // handleMessageStream 内部在 HTTP 线程上调 RagSourceCollector.begin() 创建新 Holder，立刻取出引用
            Flowable<Event> events = chatService.handleMessageStream(agentId, userId, effectiveSessionId, message);
            requestHolder = RagSourceCollector.currentHolder();
            final RagSourceCollector.Holder holderRef = requestHolder;
            final TenantScopeVO scopeRef = requestScope;
            
            // HTTP 线程拿到引用后立即清理 ThreadLocal，避免 Tomcat 线程复用导致跨请求残留
            RagSourceCollector.detach();
            
            // 用于收集流式响应内容
            StringBuilder responseCollector = new StringBuilder();

            // 收集 Agent 推理过程（Thought），用于可观测性上报
            List<String> thoughtParts = new java.util.ArrayList<>();

            // 响应字符数硬限制：maxTokens * 2（约 1 token ≈ 2 个中文字符），防止无限输出
            final int maxResponseChars = 16384;

            // SSE 发送失败标志，用于在 emitter 已关闭时取消 RxJava 订阅
            AtomicBoolean emitterClosed = new AtomicBoolean(false);

            // 注册 emitter 超时/错误回调，标记已关闭
            emitter.onTimeout(() -> emitterClosed.set(true));
            emitter.onError(e -> emitterClosed.set(true));

            // 使用 AtomicReference 持有 Disposable，解决 lambda 内引用问题
            AtomicReference<io.reactivex.rxjava3.disposables.Disposable> disposableRef =
                    new AtomicReference<>();

            disposableRef.set(events
                    // 订阅链运行在 RxJava IO worker 上：进入时把 Holder 和租户作用域注入子线程 ThreadLocal，
                    // 让链路里的工具调用 append() / setTraceId() / RequestScopeContext.get() 都能拿到正确上下文
                    .doOnSubscribe(s -> {
                        RagSourceCollector.attach(holderRef);
                        RequestScopeContext.attach(scopeRef);
                    })
                    .doFinally(() -> {
                        RagSourceCollector.detach();
                        RequestScopeContext.clear();
                    })
                    .subscribeOn(Schedulers.io())
                    .subscribe(
                            event -> {
                                // 如果 emitter 已关闭（超时/客户端断开），取消订阅停止消费事件
                                if (emitterClosed.get()) {
                                    return;
                                }
                                try {
                                    StringBuilder sb = new StringBuilder();
                                    event.content().ifPresent(c ->
                                        c.parts().ifPresent(parts ->
                                            parts.forEach(part ->
                                                part.text().ifPresent(text -> {
                                                    if (!text.isEmpty()) {
                                                        if (sb.length() > 0) sb.append("\n");
                                                        sb.append(text);
                                                        // 捕获 Agent Thought（推理过程）
                                                        thoughtParts.add(text.trim());
                                                    }
                                                })
                                            )
                                        )
                                    );
                                    if (sb.length() > 0) {
                                        // 累计响应字符数硬限制检查，超限强制关闭 SSE 流
                                        if (responseCollector.length() + sb.length() > maxResponseChars) {
                                            log.warn("流式响应超过字符数限制({} chars)，强制截断。agentId:{}", maxResponseChars, agentId);
                                            emitterClosed.set(true);
                                            emitter.send(SseEmitter.event().data("[响应已截断]"));
                                            emitter.complete();
                                            io.reactivex.rxjava3.disposables.Disposable d = disposableRef.get();
                                            if (d != null && !d.isDisposed()) {
                                                d.dispose();
                                            }
                                            return;
                                        }
                                        // 收集响应内容用于记忆存储
                                        responseCollector.append(sb).append("\n");
                                        emitter.send(SseEmitter.event().data(sb.toString()));
                                    }
                                } catch (Exception e) {
                                    // emitter.send() 失败：IllegalStateException(已完成) / IOException(Broken pipe) / 等
                                    // 统一处理：标记关闭 + 取消订阅，防止后端继续消费 ADK 事件
                                    if (!emitterClosed.getAndSet(true)) {
                                        io.reactivex.rxjava3.disposables.Disposable d = disposableRef.get();
                                        if (d != null && !d.isDisposed()) {
                                            d.dispose();
                                        }
                                        log.debug("SSE 发送失败，取消 RxJava 订阅: {}", e.getMessage());
                                    }
                                }
                            },
                            err -> {
                                try {
                                    String retrievalTraceId = holderRef == null ? "" : holderRef.getTraceId();
                                    // 上报 traceId：检索级为空（未触发 RAG）时回退到请求入口生成的 traceId
                                    String traceId = resolveReportTraceId(retrievalTraceId, requestTraceId);
                                    List<RagSourceVO> sources = holderRef == null ? null : holderRef.snapshotSources();
                                    String rewriteText = holderRef == null ? null : holderRef.getRewriteText();
                                    Integer topK = holderRef == null ? null : holderRef.getTopK();
                                    String retrievalStages = holderRef == null ? null : holderRef.getRetrievalStages();
                                    String ragStrategyVersion = holderRef == null ? null : holderRef.getRagStrategyVersion();
                                    int costMs = (int) (System.currentTimeMillis() - start);

                                    // 从 Holder 获取增强字段
                                    int promptTokens = holderRef != null ? holderRef.getPromptTokens() : 0;
                                    int completionTokens = holderRef != null ? holderRef.getCompletionTokens() : 0;
                                    String modelVersion = holderRef != null ? holderRef.getModelVersion() : null;

                                    observabilityHelper.reportChatResult(traceId, finalSessionIdForReport,
                                            finalUserIdForReport, finalAgentIdForReport, message, "",
                                            promptTokens, completionTokens, "FAIL", costMs, modelVersion);
                                    // RAG 检索：仅检索级 traceId 非空（即触发了 RAG）时上报
                                    if (!retrievalTraceId.isEmpty()) {
                                        observabilityHelper.reportRagRetrieval(traceId, finalSessionIdForReport,
                                                finalUserIdForReport, finalAgentIdForReport, message, rewriteText, topK, sources, costMs,
                                                retrievalStages, ragStrategyVersion);
                                    }
                                    // branchType 依据检索级 traceId 判断，而非上报 traceId（已兜底非空）
                                    String failBranchType = !retrievalTraceId.isEmpty() ? "RAG" : "DIRECT_ANSWER";
                                    observabilityHelper.reportAgentDecision(traceId, finalSessionIdForReport,
                                            finalUserIdForReport, null, finalAgentIdForReport, message,
                                            deriveIntentType(failBranchType), null, null,
                                            failBranchType, null, 0, 0, "FAIL", costMs, modelVersion, err.getMessage());

                                    // 上报工具调用详情 + 记忆检索结果（失败前可能已有部分工具调用）
                                    List<Map<String, Object>> failToolCalls = holderRef != null ? holderRef.getToolCalls() : Collections.emptyList();
                                    reportToolCallDetails(traceId, failToolCalls);
                                    reportMemoryRecallDetails(traceId, holderRef);
                                } catch (Throwable reportErr) {
                                    log.debug("流式对话失败上报异常: {}", reportErr.getMessage());
                                }
                                RagSourceCollector.drainHolder(holderRef);
                                emitter.completeWithError(err);
                            },
                            () -> {
                                try {
                                    // 内部收集 traceId 和 sources 仅用于可观测性上报，不再发送给前端
                                    String retrievalTraceId = holderRef == null ? "" : holderRef.getTraceId();
                                    // 上报 traceId：检索级为空（未触发 RAG）时回退到请求入口生成的 traceId
                                    String traceId = resolveReportTraceId(retrievalTraceId, requestTraceId);
                                    List<RagSourceVO> sources = holderRef == null ? null : holderRef.snapshotSources();
                                    String rewriteText = holderRef == null ? null : holderRef.getRewriteText();
                                    Integer topK = holderRef == null ? null : holderRef.getTopK();
                                    String retrievalStages = holderRef == null ? null : holderRef.getRetrievalStages();
                                    String ragStrategyVersion = holderRef == null ? null : holderRef.getRagStrategyVersion();
                                    String fullAnswer = responseCollector.toString().trim();
                                    int costMs = (int) (System.currentTimeMillis() - start);

                                    // 从 Holder 获取增强字段
                                    int promptTokens = holderRef != null ? holderRef.getPromptTokens() : 0;
                                    int completionTokens = holderRef != null ? holderRef.getCompletionTokens() : 0;
                                    String modelVersion = holderRef != null ? holderRef.getModelVersion() : null;
                                    List<Map<String, Object>> toolCalls = holderRef != null ? holderRef.getToolCalls() : Collections.emptyList();
                                    int toolCallTimes = toolCalls.size();
                                    int toolRetryTimes = holderRef != null ? holderRef.getToolRetryTimes() : 0;

                                    // 写入 Agent Thought（推理过程）到 Holder
                                    if (holderRef != null && thoughtParts.size() > 1) {
                                        // 多轮推理：前面的文本是 Thought，最后一条是最终答案
                                        String thought = String.join(" → ", thoughtParts.subList(0, thoughtParts.size() - 1));
                                        holderRef.setAgentThought(thought);
                                    } else if (holderRef != null && thoughtParts.size() == 1 && toolCalls.isEmpty()) {
                                        // 单轮推理且无工具调用：Thought 就是推理过程
                                        holderRef.setAgentThought(thoughtParts.get(0));
                                    }

                                    String decisionReason = holderRef != null ? holderRef.getAgentThought() : null;

                                    // 构建 selectedToolList JSON
                                    String selectedToolList = null;
                                    if (!toolCalls.isEmpty()) {
                                        selectedToolList = com.alibaba.fastjson.JSON.toJSONString(toolCalls.stream()
                                            .map(tc -> Map.of("toolName", tc.get("toolName"), "callOrder", tc.get("callOrder")))
                                            .collect(Collectors.toList()));
                                    }

                                    // 推导 intentType 和 branchType
                                    // 注意：branchType 依据检索级 traceId 判断，而非上报 traceId（已兜底非空）
                                    String branchType = !retrievalTraceId.isEmpty() ? "RAG" : "DIRECT_ANSWER";
                                    if ("DIRECT_ANSWER".equals(branchType) && !toolCalls.isEmpty()) {
                                        branchType = "TOOL_CALL";
                                    }
                                    String intentType = deriveIntentType(branchType);

                                    // 构建 planSteps
                                    String planSteps = null;
                                    if (!toolCalls.isEmpty()) {
                                        planSteps = com.alibaba.fastjson.JSON.toJSONString(toolCalls.stream()
                                            .map(tc -> Map.of("step", tc.get("toolName"), "order", tc.get("callOrder"), "status", tc.get("status")))
                                            .collect(Collectors.toList()));
                                    }

                                    RagSourceCollector.drainHolder(holderRef);

                                    // 上报问答结果 + RAG 检索 + Agent 决策
                                    try {
                                        observabilityHelper.reportChatResult(traceId, finalSessionIdForReport,
                                                finalUserIdForReport, finalAgentIdForReport, message, fullAnswer,
                                                promptTokens, completionTokens, "SUCCESS", costMs, modelVersion);
                                        // RAG 检索：仅检索级 traceId 非空（即触发了 RAG）时上报
                                        if (!retrievalTraceId.isEmpty()) {
                                            observabilityHelper.reportRagRetrieval(traceId, finalSessionIdForReport,
                                                    finalUserIdForReport, finalAgentIdForReport, message, rewriteText, topK, sources, costMs,
                                                    retrievalStages, ragStrategyVersion);
                                        }
                                        observabilityHelper.reportAgentDecision(traceId, finalSessionIdForReport,
                                                finalUserIdForReport, null, finalAgentIdForReport, message,
                                                intentType, selectedToolList, decisionReason,
                                                branchType, planSteps, toolCallTimes, toolRetryTimes,
                                                "SUCCESS", costMs, modelVersion, null);

                                        // 上报工具调用详情 + 记忆检索结果
                                        reportToolCallDetails(traceId, toolCalls);
                                        reportMemoryRecallDetails(traceId, holderRef);
                                    } catch (Throwable reportErr) {
                                        log.debug("流式对话成功上报异常: {}", reportErr.getMessage());
                                    }

                                    // 异步存储对话记忆
                                    String fullResponse = responseCollector.toString().trim();
                                    if (!fullResponse.isEmpty()) {
                                        final String finalAgentId = agentId;
                                        final String finalUserId = userId;
                                        final String finalMessage = message;
                                        final TenantScopeVO finalScope = scopeRef;
                                        // 提交到记忆线程池存储，避免阻塞 SSE 完成，同时复用线程池防止高并发下线程爆炸
                                        memoryTaskExecutor.execute(() -> {
                                            RequestScopeContext.attach(finalScope);
                                            try {
                                                chatService.storeStreamConversationMemory(
                                                    finalUserId, finalAgentId, effectiveSessionId,
                                                    finalMessage, fullResponse);
                                            } catch (Exception e) {
                                                log.warn("流式对话记忆存储失败", e);
                                            } finally {
                                                RequestScopeContext.clear();
                                            }
                                        });
                                    }
                                } catch (Exception sendErr) {
                                    log.warn("流式对话完成处理失败", sendErr);
                                }
                                emitter.complete();
                            }
                    )
            );
        } catch (Exception e) {
            log.error("流式对话失败", e);
            RagSourceCollector.drainHolder(requestHolder);
            RagSourceCollector.detach();
            // 通过 SSE error 事件通知客户端，而非 completeWithError
            // completeWithError 会触发 Tomcat 异步错误分发，因 Content-Type 已是 text/event-stream
            // 导致 GlobalExceptionHandler 尝试写 JSON 时再次异常（No converter for text/event-stream）
            try {
                emitter.send(SseEmitter.event().name("error").data("系统繁忙，请稍后重试"));
            } catch (Exception ignored) {
                // 客户端可能已断开连接，忽略发送失败
            }
            emitter.complete();
        }
        return emitter;
    }

    private String resolveSessionId(String sessionId, String agentId, String userId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.startsWith("temp_")) {
            return chatService.createSession(agentId, userId);
        }
        return sessionId;
    }


}
