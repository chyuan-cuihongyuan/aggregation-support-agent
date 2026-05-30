package cn.chyuan.ai.domain.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Agent 记忆定时任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.memory.enabled", havingValue = "true", matchIfMissing = false)
public class AgentMemoryScheduler {

    private final AgentMemoryService agentMemoryService;

    /**
     * 定时执行记忆整合。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledConsolidate() {
        log.info("开始定时记忆整合任务");
        // TODO: 遍历所有租户和用户执行整合，调用 agentMemoryService.consolidate(tenantId, userId)。
    }
}
