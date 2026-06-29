package cn.chyuan.ai.infrastructure.utils;

import cn.chyuan.ai.domain.rag.model.valobj.RagSourceVO;
import cn.chyuan.ai.observability.client.ObservabilityClient;
import cn.chyuan.ai.observability.client.model.AgentDecisionReport;
import cn.chyuan.ai.observability.client.model.ChatResultReport;
import cn.chyuan.ai.observability.client.model.MemoryRecallLogReport;
import cn.chyuan.ai.observability.client.model.RagRetrievalReport;
import cn.chyuan.ai.observability.client.model.ToolCallLogReport;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ObservabilityHelper {

    private static final String SOURCE_SERVICE = "AGENT";

    @Resource
    private ObservabilityClient observabilityClient;

    public void reportAgentDecision(String traceId, String sessionId, String userId,
                                     String tenantId, String agentId, String userQuery,
                                     String branchType, String status, Integer costTimeMs,
                                     String errorMessage) {
        reportAgentDecision(traceId, sessionId, userId, tenantId, agentId, userQuery,
                null, null, null, branchType, null, 0, 0, status, costTimeMs, null, errorMessage);
    }

    public void reportAgentDecision(String traceId, String sessionId, String userId,
                                     String tenantId, String agentId, String userQuery,
                                     String intentType, String selectedToolList,
                                     String decisionReason, String branchType,
                                     String planSteps, Integer toolCallTimes,
                                     Integer toolRetryTimes, String status,
                                     Integer costTimeMs, String modelVersion,
                                     String errorMessage) {
        try {
            AgentDecisionReport report = AgentDecisionReport.builder()
                    .traceId(traceId).sourceService(SOURCE_SERVICE)
                    .tenantId(tenantId).ownerUserId(userId)
                    .sessionId(sessionId).agentId(agentId)
                    .userQuery(userQuery).intentType(intentType)
                    .selectedToolList(selectedToolList).decisionReason(decisionReason)
                    .branchType(branchType).planSteps(planSteps)
                    .toolCallTimes(toolCallTimes).toolRetryTimes(toolRetryTimes)
                    .agentStatus(status).costTimeMs(costTimeMs)
                    .modelVersion(modelVersion).errorMessage(errorMessage).build();
            observabilityClient.reportAgentDecision(report);
        } catch (Throwable e) {
            log.debug("observability report failed: {}", e.getMessage());
        }
    }

    public void reportChatResult(String traceId, String sessionId, String userId, String agentId,
                                  String question, String answer, String status,
                                  Integer costTimeMs) {
        reportChatResult(traceId, sessionId, userId, agentId, question, answer, 0, 0, status, costTimeMs, null);
    }

    public void reportChatResult(String traceId, String sessionId, String userId, String agentId,
                                  String question, String answer,
                                  Integer promptTokens, Integer completionTokens,
                                  String status, Integer costTimeMs, String modelVersion) {
        try {
            ChatResultReport report = ChatResultReport.builder()
                    .traceId(traceId).sourceService(SOURCE_SERVICE)
                    .ownerUserId(userId).sessionId(sessionId).agentId(agentId)
                    .question(question).answer(answer)
                    .promptTokens(promptTokens).completionTokens(completionTokens)
                    .finalStatus(status).totalCostTimeMs(costTimeMs)
                    .modelVersion(modelVersion).build();
            observabilityClient.reportChatResult(report);
        } catch (Throwable e) {
            log.debug("observability report failed: {}", e.getMessage());
        }
    }

    /**
     * 上报 RAG 检索日志 — 把本次请求累积的检索证据序列化为 sourceDocs / rerankScores。
     * traceId 为空说明本次对话未触发检索，直接跳过。
     */
    public void reportRagRetrieval(String traceId, String sessionId, String userId, String agentId,
                                   String queryText, String rewriteText, Integer topK,
                                   List<RagSourceVO> sources, Integer costTimeMs) {
        reportRagRetrieval(traceId, sessionId, userId, agentId, queryText, rewriteText, topK, sources, costTimeMs, null, null);
    }

    public void reportRagRetrieval(String traceId, String sessionId, String userId, String agentId,
                                   String queryText, String rewriteText, Integer topK,
                                   List<RagSourceVO> sources, Integer costTimeMs,
                                   String retrievalStages, String ragStrategyVersion) {
        if (traceId == null || traceId.isEmpty()) {
            return;
        }
        try {
            int count = sources == null ? 0 : sources.size();
            String sourceDocs = sources == null ? "[]" : JSON.toJSONString(sources);
            String rerankScores = sources == null ? "[]" : JSON.toJSONString(
                    sources.stream()
                            .map(s -> {
                                java.util.Map<String, Object> m = new java.util.HashMap<>();
                                m.put("chunkId", s.getChunkId());
                                m.put("score", s.getScore());
                                return m;
                            })
                            .collect(Collectors.toList()));
            RagRetrievalReport report = RagRetrievalReport.builder()
                    .traceId(traceId).sourceService(SOURCE_SERVICE)
                    .ownerUserId(userId).sessionId(sessionId).agentId(agentId)
                    .queryText(queryText).rewriteText(rewriteText)
                    .retrievalTopk(topK).retrievalCount(count)
                    .sourceDocs(sourceDocs).rerankScores(rerankScores)
                    .emptyRetrieval(count == 0 ? 1 : 0)
                    .retrievalCostMs(costTimeMs)
                    .retrievalStages(retrievalStages)
                    .ragStrategyVersion(ragStrategyVersion).build();
            observabilityClient.reportRagRetrieval(report);
        } catch (Throwable e) {
            log.debug("observability rag retrieval report failed: {}", e.getMessage());
        }
    }

    /**
     * 上报工具调用日志
     */
    public void reportToolCall(String traceId, String spanId, String parentSpanId,
                               String toolName, String toolInput, String toolOutput,
                               String status, Integer costTimeMs, String errorMessage,
                               Integer callOrder) {
        try {
            ToolCallLogReport report = ToolCallLogReport.builder()
                    .traceId(traceId).spanId(spanId).parentSpanId(parentSpanId)
                    .toolName(toolName).toolInput(toolInput).toolOutput(toolOutput)
                    .status(status).costTimeMs(costTimeMs).errorMessage(errorMessage)
                    .callOrder(callOrder).build();
            observabilityClient.reportToolCall(report);
        } catch (Throwable e) {
            log.debug("observability tool call report failed: {}", e.getMessage());
        }
    }

    /**
     * 上报记忆检索日志
     */
    public void reportMemoryRecall(String traceId, String queryText,
                                   Integer sessionMemoryCount, Integer agentMemoryCount,
                                   String sessionMemoryScores, String agentMemoryScores,
                                   String injectContent, Integer costTimeMs) {
        try {
            MemoryRecallLogReport report = MemoryRecallLogReport.builder()
                    .traceId(traceId).queryText(queryText)
                    .sessionMemoryCount(sessionMemoryCount).agentMemoryCount(agentMemoryCount)
                    .sessionMemoryScores(sessionMemoryScores).agentMemoryScores(agentMemoryScores)
                    .injectContent(injectContent).costTimeMs(costTimeMs).build();
            observabilityClient.reportMemoryRecall(report);
        } catch (Throwable e) {
            log.debug("observability memory recall report failed: {}", e.getMessage());
        }
    }
}
