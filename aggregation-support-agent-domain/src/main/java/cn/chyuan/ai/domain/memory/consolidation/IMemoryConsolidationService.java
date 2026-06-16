package cn.chyuan.ai.domain.memory.consolidation;

import cn.chyuan.ai.domain.memory.longterm.abstraction.AbstractionResult;
import cn.chyuan.ai.domain.memory.model.valobj.ConsolidationReport;

/**
 * 记忆整合服务接口（增强版）
 * 
 * 支持五种整合操作：去重、合并、抽象提炼、过期清理、冲突消解。
 */
public interface IMemoryConsolidationService {
    
    /**
     * 执行完整整合流程
     */
    ConsolidationReport consolidate(String tenantId, String userId);
    
    /**
     * 去重
     */
    int deduplicate(String tenantId, String userId);
    
    /**
     * 冲突消解
     */
    int resolveConflicts(String tenantId, String userId);
    
    /**
     * 抽象提炼（情节→语义）
     */
    AbstractionResult abstractToSemantics(String tenantId, String userId);
    
    /**
     * 过期清理
     */
    int cleanExpired(String tenantId, String userId);
}
