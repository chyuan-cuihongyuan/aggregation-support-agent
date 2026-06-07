package cn.chyuan.ai.infrastructure.utils;

import cn.chyuan.ai.domain.rag.model.valobj.RagSourceVO;
import cn.chyuan.ai.observability.client.ObservabilityClient;
import cn.chyuan.ai.observability.client.model.AgentDecisionReport;
import cn.chyuan.ai.observability.client.model.ChatResultReport;
import cn.chyuan.ai.observability.client.model.RagRetrievalReport;
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
        try {
            AgentDecisionReport report = AgentDecisionReport.builder()
                    .traceId(traceId).sourceService(SOURCE_SERVICE)
                    .tenantId(tenantId).ownerUserId(userId)
                    .sessionId(sessionId).agentId(agentId)
                    .userQuery(userQuery).branchType(branchType)
                    .agentStatus(status).costTimeMs(costTimeMs)
                    .errorMessage(errorMessage).build();
            observabilityClient.reportAgentDecision(report);
        } catch (Exception e) {
            log.debug("observability report failed: {}", e.getMessage());
        }
    }

    public void reportChatResult(String traceId, String sessionId, String userId,
                                  String question, String answer, String status,
                                  Integer costTimeMs) {
        try {
            ChatResultReport report = ChatResultReport.builder()
                    .traceId(traceId).sourceService(SOURCE_SERVICE)
                    .ownerUserId(userId).sessionId(sessionId)
                    .question(question).answer(answer)
                    .finalStatus(status).totalCostTimeMs(costTimeMs).build();
            observabilityClient.reportChatResult(report);
        } catch (Exception e) {
            log.debug("observability report failed: {}", e.getMessage());
        }
    }

    /**
     * 上报 RAG 检索日志 — 把本次请求累积的检索证据序列化为 sourceDocs / rerankScores。
     * traceId 为空说明本次对话未触发检索，直接跳过。
     */
    public void reportRagRetrieval(String traceId, String sessionId, String userId,
                                   String queryText, String rewriteText, Integer topK,
                                   List<RagSourceVO> sources, Integer costTimeMs) {
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
                    .ownerUserId(userId).sessionId(sessionId)
                    .queryText(queryText).rewriteText(rewriteText)
                    .retrievalTopk(topK).retrievalCount(count)
                    .sourceDocs(sourceDocs).rerankScores(rerankScores)
                    .emptyRetrieval(count == 0 ? 1 : 0)
                    .retrievalCostMs(costTimeMs).build();
            observabilityClient.reportRagRetrieval(report);
        } catch (Exception e) {
            log.debug("observability rag retrieval report failed: {}", e.getMessage());
        }
    }
}
