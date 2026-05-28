package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.IAgentService;
import cn.chyuan.ai.api.dto.*;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.service.IChatService;
import cn.chyuan.ai.domain.rag.model.valobj.RagSourceVO;
import cn.chyuan.ai.domain.rag.support.RagSourceCollector;
import cn.chyuan.ai.infrastructure.utils.ObservabilityHelper;
import cn.chyuan.ai.trigger.support.CurrentUserSupport;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 *
 * @author xiaofuge bugstack.cn @小傅哥
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
        try {
            String userId = CurrentUserSupport.requireUserIdString(request);
            log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), userId);
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), userId);
            }

            List<String> messages;
            List<RagSourceVO> sources;
            String traceId;
            try {
                messages = chatService.handleMessage(requestDTO.getAgentId(), userId, sessionId, requestDTO.getMessage());
            } finally {
                // 出口统一 drain，确保异常路径也清理 ThreadLocal
                traceId = RagSourceCollector.getTraceId();
                sources = RagSourceCollector.drain();
            }

            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setContent(String.join("\n", messages));
            responseDTO.setTraceId(traceId);
            responseDTO.setSources(toSourceDTOList(sources));

            observabilityHelper.reportChatResult(traceId, sessionId, userId, requestDTO.getMessage(), responseDTO.getContent(), "SUCCESS", (int)(System.currentTimeMillis() - start));

            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常", e);
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(HttpServletRequest request, @RequestBody ChatRequestDTO requestDTO) {
        SseEmitter emitter = new SseEmitter(3 * 60 * 1000L);
        RagSourceCollector.Holder requestHolder = null;
        try {
            String userId = CurrentUserSupport.requireUserIdString(request);
            String agentId = requestDTO.getAgentId();
            String sessionId = requestDTO.getSessionId();
            String message = requestDTO.getMessage();
            
            log.info("流式对话 agentId:{} userId:{} sessionId:{}", agentId, userId, sessionId);
            
            // handleMessageStream 内部在 HTTP 线程上调 RagSourceCollector.begin() 创建新 Holder，立刻取出引用
            Flowable<Event> events = chatService.handleMessageStream(agentId, userId, sessionId, message);
            requestHolder = RagSourceCollector.currentHolder();
            final RagSourceCollector.Holder holderRef = requestHolder;
            
            // HTTP 线程拿到引用后立即清理 ThreadLocal，避免 Tomcat 线程复用导致跨请求残留
            RagSourceCollector.detach();
            
            // 用于收集流式响应内容
            StringBuilder responseCollector = new StringBuilder();
            
            events.subscribeOn(Schedulers.io())
                    // 订阅链运行在 RxJava IO worker 上：进入时把 Holder 注入子线程 ThreadLocal，
                    // 让链路里的工具调用 append() / setTraceId() 能拿到正确 Holder；结束时清理
                    .doOnSubscribe(s -> RagSourceCollector.attach(holderRef))
                    .doFinally(RagSourceCollector::detach)
                    .subscribe(
                            event -> {
                                try {
                                    StringBuilder sb = new StringBuilder();
                                    event.content().ifPresent(c ->
                                        c.parts().ifPresent(parts ->
                                            parts.forEach(part ->
                                                part.text().ifPresent(text -> {
                                                    if (!text.isEmpty()) {
                                                        if (sb.length() > 0) sb.append("\n");
                                                        sb.append(text);
                                                    }
                                                })
                                            )
                                        )
                                    );
                                    if (sb.length() > 0) {
                                        // 收集响应内容用于记忆存储
                                        responseCollector.append(sb).append("\n");
                                        emitter.send(SseEmitter.event().data(sb.toString()));
                                    }
                                } catch (Exception e) {
                                    log.error("流式对话发送失败", e);
                                }
                            },
                            err -> {
                                RagSourceCollector.drainHolder(holderRef);
                                emitter.completeWithError(err);
                            },
                            () -> {
                                try {
                                    String traceId = holderRef == null ? "" : holderRef.getTraceId();
                                    List<RagSourceVO> sources = RagSourceCollector.drainHolder(holderRef);
                                    Map<String, Object> payload = new HashMap<>();
                                    payload.put("traceId", traceId);
                                    payload.put("sources", toSourceDTOList(sources));
                                    emitter.send(SseEmitter.event().name("sources").data(payload));
                                    
                                    // 异步存储对话记忆
                                    String fullResponse = responseCollector.toString().trim();
                                    if (!fullResponse.isEmpty()) {
                                        final String finalAgentId = agentId;
                                        final String finalUserId = userId;
                                        final String finalSessionId = sessionId;
                                        final String finalMessage = message;
                                        // 使用独立线程存储记忆，避免阻塞 SSE 完成
                                        new Thread(() -> {
                                            try {
                                                ((ChatService) chatService).storeStreamConversationMemory(
                                                    finalUserId, finalAgentId, finalSessionId, 
                                                    finalMessage, fullResponse);
                                            } catch (Exception e) {
                                                log.warn("流式对话记忆存储失败", e);
                                            }
                                        }, "memory-store-stream").start();
                                    }
                                } catch (Exception sendErr) {
                                    log.warn("追发 sources 事件失败", sendErr);
                                }
                                emitter.complete();
                            }
                    );
        } catch (Exception e) {
            log.error("流式对话失败", e);
            RagSourceCollector.drainHolder(requestHolder);
            RagSourceCollector.detach();
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * 将领域层 RAG 证据 VO 列表转为 API 层 DTO 列表，避免 api 模块依赖 domain 模块
     */
    private List<RagSourceDTO> toSourceDTOList(List<RagSourceVO> sources) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }
        return sources.stream().map(vo -> RagSourceDTO.builder()
                .documentId(vo.getDocumentId())
                .documentName(vo.getDocumentName())
                .chunkId(vo.getChunkId())
                .chunkIndex(vo.getChunkIndex())
                .score(vo.getScore())
                .retrievalType(vo.getRetrievalType())
                .snippet(vo.getSnippet())
                .build()).collect(Collectors.toList());
    }

}
