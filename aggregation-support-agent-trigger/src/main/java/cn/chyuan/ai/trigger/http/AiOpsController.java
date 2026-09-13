package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.AiOpsRequestDTO;
import cn.chyuan.ai.domain.agent.service.IChatService;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.rag.support.RagSourceCollector;
import cn.chyuan.ai.trigger.support.CurrentUserSupport;
import cn.chyuan.ai.trigger.support.TenantScopeSupport;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

/**
 * AIOps 智能运维控制器 — 提供一键告警分析接口，SSE 流式返回运维报告
 * <p>
 * 调用 AIOps 智能体（agentId=200002），通过 Planner-Executor 串行工作流：
 * 1. Planner 调用工具收集告警、日志、文档信息
 * 2. Executor 根据分析结果生成 Markdown 格式运维报告
 * <p>
 * 超时设置为 10 分钟（AIOps 分析可能耗时较长）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class AiOpsController {

    /** AIOps 分析超时时间：10 分钟（毫秒） */
    private static final long AIOPS_TIMEOUT_MS = 10 * 60 * 1000L;

    /** 默认使用 AIOps 智能体 ID */
    private static final String DEFAULT_AIOPS_AGENT_ID = "200002";

    @Resource
    private IChatService chatService;

    /**
     * AIOps 一键告警分析 — 以 SSE 流式方式返回分析报告
     *
     * @param requestDTO AIOps 请求（包含 agentId、userId、sessionId、alertDescription）
     * @return ResponseBodyEmitter（SSE 流式响应）
     */
    @RequestMapping(value = "ai_ops", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter aiOpsAnalysis(HttpServletRequest request, @RequestBody AiOpsRequestDTO requestDTO) {
        // 设置 10 分钟超时的 SSE Emitter
        SseEmitter emitter = new SseEmitter(AIOPS_TIMEOUT_MS);
        RagSourceCollector.Holder requestHolder = null;

        try {
            String agentId = requestDTO.getAgentId() != null ? requestDTO.getAgentId() : DEFAULT_AIOPS_AGENT_ID;
            String userId = CurrentUserSupport.requireUserIdString(request);
            String sessionId = requestDTO.getSessionId();
            String message = requestDTO.getAlertDescription() != null ? requestDTO.getAlertDescription() : "请分析当前所有活动告警";
            TenantScopeVO requestScope = TenantScopeSupport.currentScope(request);

            log.info("AIOps 分析请求: agentId={}, userId={}, sessionId={}", agentId, userId, sessionId);

            // 如果没有 sessionId，先创建会话
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(agentId, userId);
            }

            // 调用对话服务的流式接口，获取 AIOps 分析结果
            final String finalSessionId = sessionId;
            Flowable<Event> events = chatService.handleMessageStream(agentId, userId, finalSessionId, message);
            requestHolder = RagSourceCollector.currentHolder();
            final RagSourceCollector.Holder holderRef = requestHolder;
            final TenantScopeVO scopeRef = requestScope;
            RagSourceCollector.detach();

            // 订阅事件流，将每个事件内容通过 SSE 推送到前端
            // SELFLOOP2 loop-250：断连处置——emitter 关闭标志 + dispose 上游订阅，
            // 与 chat_stream（AgentServiceController）防线对齐，防止客户端断开后继续消耗 LLM
            java.util.concurrent.atomic.AtomicBoolean emitterClosed = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicReference<io.reactivex.rxjava3.disposables.Disposable> disposableRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            emitter.onTimeout(() -> emitterClosed.set(true));
            emitter.onError(e -> emitterClosed.set(true));
            Disposable disposable = events
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
                                try {
                                    if (emitterClosed.get()) {
                                        RagSourceCollector.drainHolder(holderRef);
                                        disposableRef.get().dispose();
                                        return;
                                    }
                                    String content = event.stringifyContent();
                                    if (content != null && !content.isEmpty()) {
                                        emitter.send(SseEmitter.event().data(content));
                                    }
                                } catch (Exception e) {
                                    log.error("AIOps SSE 发送失败", e);
                                    // 发送失败（客户端断连 broken pipe）：置位 + dispose 上游，停止消耗 LLM
                                    if (!emitterClosed.getAndSet(true)) {
                                        io.reactivex.rxjava3.disposables.Disposable d = disposableRef.get();
                                        if (d != null && !d.isDisposed()) {
                                            d.dispose();
                                        }
                                    }
                                }
                            },
                            err -> {
                                RagSourceCollector.drainHolder(holderRef);
                                emitter.completeWithError(err);
                            },
                            () -> {
                                RagSourceCollector.drainHolder(holderRef);
                                emitter.complete();
                            }
                    );
            disposableRef.set(disposable);

        } catch (AppException e) {
            log.error("AIOps 分析异常", e);
            RagSourceCollector.drainHolder(requestHolder);
            RagSourceCollector.detach();
            try {
                emitter.send(SseEmitter.event().data("分析失败: " + e.getInfo()));
            } catch (Exception ignored) {
            }
            emitter.complete();
        } catch (Exception e) {
            log.error("AIOps 分析失败", e);
            RagSourceCollector.drainHolder(requestHolder);
            RagSourceCollector.detach();
            emitter.completeWithError(e);
        }

        return emitter;
    }

}
