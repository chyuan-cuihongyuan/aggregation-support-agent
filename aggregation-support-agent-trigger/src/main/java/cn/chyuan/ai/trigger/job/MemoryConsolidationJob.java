package cn.chyuan.ai.trigger.job;

import cn.chyuan.ai.domain.memory.adapter.repository.IAgentMemoryRepository;
import cn.chyuan.ai.domain.memory.consolidation.IMemoryConsolidationService;
import cn.chyuan.ai.domain.memory.model.valobj.ConsolidationReport;
import cn.chyuan.ai.domain.memory.model.valobj.TenantUserPair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 记忆整合定时任务
 * 
 * 每天凌晨 3 点执行，遍历所有租户-用户对，执行完整记忆整合流程。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.memory.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class MemoryConsolidationJob {
    
    private final IMemoryConsolidationService consolidationService;
    private final IAgentMemoryRepository memoryRepository;
    
    @Scheduled(cron = "${agent.memory.consolidation.cron:0 0 3 * * ?}")
    public void consolidateAllUsers() {
        log.info("开始执行记忆整合定时任务");
        
        List<TenantUserPair> pairs = memoryRepository.findAllTenantUserPairs();
        log.info("发现 {} 个租户-用户对需要整合", pairs.size());
        
        int success = 0;
        int failed = 0;
        
        for (TenantUserPair pair : pairs) {
            try {
                ConsolidationReport report = consolidationService.consolidate(
                    pair.getTenantId(),
                    pair.getUserId()
                );
                log.info("记忆整合完成: tenant={}, user={}, report={}",
                    pair.getTenantId(), pair.getUserId(), report);
                success++;
            } catch (Exception e) {
                log.error("记忆整合失败: tenant={}, user={}",
                    pair.getTenantId(), pair.getUserId(), e);
                failed++;
            }
        }
        
        log.info("记忆整合定时任务完成: success={}, failed={}", success, failed);
    }
}
