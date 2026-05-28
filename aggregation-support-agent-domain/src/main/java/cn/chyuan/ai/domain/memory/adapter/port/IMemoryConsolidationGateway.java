package cn.chyuan.ai.domain.memory.adapter.port;

import cn.chyuan.ai.domain.memory.model.valobj.ConsolidationDecision;

/**
 * 记忆整合网关接口 — 使用 LLM 决定如何处理相似记忆
 */
public interface IMemoryConsolidationGateway {
    
    /**
     * 决定如何处理相似记忆
     *
     * @param existingContent 现有记忆内容
     * @param newContent      新记忆内容
     * @return 整合决策
     */
    ConsolidationDecision decide(String existingContent, String newContent);
}
