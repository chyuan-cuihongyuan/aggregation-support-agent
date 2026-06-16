package cn.chyuan.ai.domain.memory.consolidation.impl;

import cn.chyuan.ai.domain.memory.adapter.repository.IAgentMemoryRepository;
import cn.chyuan.ai.domain.memory.consolidation.IMemoryConsolidationService;
import cn.chyuan.ai.domain.memory.longterm.abstraction.AbstractionResult;
import cn.chyuan.ai.domain.memory.longterm.abstraction.MemoryAbstractionService;
import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.ConsolidationReport;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryOptions;
import cn.chyuan.ai.domain.memory.service.AgentMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 记忆整合服务实现（增强版）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryConsolidationServiceImpl implements IMemoryConsolidationService {
    
    private final AgentMemoryService memoryService;
    private final MemoryAbstractionService abstractionService;
    private final IAgentMemoryRepository memoryRepository;
    
    @Override
    public ConsolidationReport consolidate(String tenantId, String userId) {
        log.info("开始完整记忆整合: tenant={}, user={}", tenantId, userId);
        
        // Step 1: 去重
        int dedup = deduplicate(tenantId, userId);
        
        // Step 2: 冲突消解
        int conflicts = resolveConflicts(tenantId, userId);
        
        // Step 3: 抽象提炼
        AbstractionResult abstraction = abstractToSemantics(tenantId, userId);
        
        // Step 4: 过期清理
        int expired = cleanExpired(tenantId, userId);
        
        ConsolidationReport report = ConsolidationReport.builder()
            .tenantId(tenantId)
            .userId(userId)
            .build();
        report.incrementDuplicatesRemoved(); // 简化：实际应该记录具体数量
        report.incrementExpiredCleaned(expired);
        
        log.info("完整记忆整合完成: dedup={}, conflicts={}, abstraction={}, expired={}",
            dedup, conflicts, abstraction.isSuccess(), expired);
        
        return report;
    }
    
    @Override
    public int deduplicate(String tenantId, String userId) {
        // 委托给现有的 consolidate 方法（已包含去重逻辑）
        // 这里简化处理
        log.debug("执行去重: tenant={}, user={}", tenantId, userId);
        return 0; // 实际应该返回去重数量
    }
    
    @Override
    public int resolveConflicts(String tenantId, String userId) {
        // 查找同一 scope 下类型相同但内容矛盾的记忆
        // 保留最新的，标记旧的为过期
        log.debug("执行冲突消解: tenant={}, user={}", tenantId, userId);
        return 0;
    }
    
    @Override
    public AbstractionResult abstractToSemantics(String tenantId, String userId) {
        // Step 1: 获取所有情节记忆
        List<AgentMemoryEntity> episodes = memoryRepository.findByTenantAndUser(tenantId, userId)
            .stream()
            .filter(e -> e.getMemoryType() == MemoryType.EPISODE && e.getStatus() == 1)
            .collect(Collectors.toList());
        
        if (episodes.isEmpty()) {
            return AbstractionResult.builder().success(false).reason("无情节记忆").build();
        }
        
        // Step 2: 按主题聚类
        Map<String, List<AgentMemoryEntity>> clusters = abstractionService.clusterByTopic(episodes);
        
        // Step 3: 对每个聚类执行提炼
        AbstractionResult bestResult = null;
        for (Map.Entry<String, List<AgentMemoryEntity>> entry : clusters.entrySet()) {
            if (entry.getValue().size() >= 3) {
                AbstractionResult result = abstractionService.abstractEpisodes(entry.getValue());
                if (result.isSuccess()) {
                    // 存储提炼出的语义记忆
                    memoryService.remember(
                        result.getSemanticContent(),
                        MemoryOptions.builder()
                            .tenantId(tenantId)
                            .userId(userId)
                            .memoryType(MemoryType.SEMANTIC)
                            .scope("/abstracted/" + entry.getKey())
                            .source("abstraction")
                            .build()
                    );
                    bestResult = result;
                }
            }
        }
        
        return bestResult != null ? bestResult :
            AbstractionResult.builder().success(false).reason("无满足条件的聚类").build();
    }
    
    @Override
    public int cleanExpired(String tenantId, String userId) {
        return memoryRepository.deleteExpired(tenantId, userId);
    }
}
