package cn.chyuan.ai.infrastructure.utils;

import cn.chyuan.ai.observability.client.ObservabilityClient;
import cn.chyuan.ai.observability.client.model.AgentDecisionReport;
import cn.chyuan.ai.observability.client.model.ChatResultReport;
import cn.chyuan.ai.observability.client.model.RagRetrievalReport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
}
