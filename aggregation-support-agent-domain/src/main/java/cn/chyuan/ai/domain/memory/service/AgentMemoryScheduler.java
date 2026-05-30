package cn.chyuan.ai.domain.memory.service;

import cn.chyuan.ai.domain.memory.adapter.repository.IAgentMemoryRepository;
import cn.chyuan.ai.domain.memory.model.valobj.ConsolidationReport;
import cn.chyuan.ai.domain.memory.model.valobj.TenantUserPair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 记忆定时任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.memory.enabled", havingValue = "true", matchIfMissing = false)
public class AgentMemoryScheduler {

    private final AgentMemoryService agentMemoryService;
    private final IAgentMemoryRepository memoryRepository;

    /**
     * 定时执行记忆整合。
     * <p>
     * 每天凌晨 3 点执行，遍历所有拥有有效记忆的租户-用户对，
     * 对每个用户执行记忆整合（去重、合并、清理过期）。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledConsolidate() {
        log.info("开始定时记忆整合任务");

        // 1. 查询所有有效的租户-用户对
        List<TenantUserPair> tenantUserPairs = memoryRepository.findAllTenantUserPairs();
        if (tenantUserPairs.isEmpty()) {
            log.info("没有需要整合的记忆，任务结束");
            return;
        }

        log.info("共发现 {} 个租户-用户对需要整合", tenantUserPairs.size());

        // 2. 遍历执行整合
        int successCount = 0;
        int failCount = 0;
        int totalDuplicatesRemoved = 0;
        int totalMerged = 0;
        int totalExpiredCleaned = 0;

        for (TenantUserPair pair : tenantUserPairs) {
            try {
                ConsolidationReport report = agentMemoryService.consolidate(
                        pair.getTenantId(), pair.getUserId());

                successCount++;
                totalDuplicatesRemoved += report.getDuplicatesRemoved();
                totalMerged += report.getMerged();
                totalExpiredCleaned += report.getExpiredCleaned();

                log.info("租户-用户整合完成: tenant={}, user={}, 去重={}, 合并={}, 过期清理={}",
                        pair.getTenantId(), pair.getUserId(),
                        report.getDuplicatesRemoved(), report.getMerged(),
                        report.getExpiredCleaned());
            } catch (Exception e) {
                failCount++;
                log.error("租户-用户整合失败: tenant={}, user={}",
                        pair.getTenantId(), pair.getUserId(), e);
            }
        }

        // 3. 输出汇总报告
        log.info("定时记忆整合任务完成: 总计={}, 成功={}, 失败={}, 总去重={}, 总合并={}, 总过期清理={}",
                tenantUserPairs.size(), successCount, failCount,
                totalDuplicatesRemoved, totalMerged, totalExpiredCleaned);
    }
}
