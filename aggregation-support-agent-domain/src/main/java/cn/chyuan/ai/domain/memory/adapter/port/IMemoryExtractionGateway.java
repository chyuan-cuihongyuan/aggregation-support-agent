package cn.chyuan.ai.domain.memory.adapter.port;

import cn.chyuan.ai.domain.memory.model.valobj.ExtractedFact;

import java.util.List;

/**
 * 记忆提取网关接口 — 使用 LLM 从对话中提取原子事实
 */
public interface IMemoryExtractionGateway {
    
    /**
     * 从内容中提取原子事实
     *
     * @param content 原始内容
     * @return 提取的事实列表
     */
    List<ExtractedFact> extractFacts(String content);
    
    /**
     * 评估内容重要性
     *
     * @param content 内容
     * @return 重要性分数 (0-1)
     */
    Float assessImportance(String content);
}
