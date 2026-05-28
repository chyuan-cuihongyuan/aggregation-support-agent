package cn.chyuan.ai.domain.memory.service;

import cn.chyuan.ai.domain.memory.model.valobj.ConsolidationReport;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryOptions;
import cn.chyuan.ai.domain.memory.model.valobj.RecallOptions;

import java.util.List;

/**
 * Agent 记忆服务接口
 */
public interface AgentMemoryService {
    
    /**
     * 存储记忆 (自动提取关键信息)
     *
     * @param content 原始内容
     * @param options 记忆选项
     */
    void remember(String content, MemoryOptions options);
    
    /**
     * 批量存储记忆
     *
     * @param contents 原始内容列表
     * @param options  记忆选项
     */
    void rememberBatch(List<String> contents, MemoryOptions options);
    
    /**
     * 检索相关记忆
     *
     * @param query   查询内容
     * @param options 检索选项
     * @return 匹配的记忆列表
     */
    List<MemoryMatch> recall(String query, RecallOptions options);
    
    /**
     * 遗忘 (软删除)
     *
     * @param memoryId 记忆ID
     */
    void forget(String memoryId);
    
    /**
     * 按作用域遗忘
     *
     * @param scope 作用域
     */
    void forgetByScope(String scope);
    
    /**
     * 执行记忆整合 (去重/合并)
     *
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @return 整合报告
     */
    ConsolidationReport consolidate(String tenantId, String userId);
}
