package cn.chyuan.ai.domain.memory.retrieval;

import cn.chyuan.ai.domain.memory.model.valobj.ConsolidationReport;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryOptions;
import cn.chyuan.ai.domain.memory.model.valobj.RecallOptions;

import java.util.List;

/**
 * 统一记忆服务接口
 * 
 * 协调四层记忆的统一入口，提供记住、召回、主动检索、遗忘、整合等功能。
 */
public interface IUnifiedMemoryService {
    
    /**
     * 记住（自动判断记忆层级和类型）
     */
    void remember(String content, MemoryOptions options);
    
    /**
     * 召回（混合检索：向量+知识图谱）
     */
    List<MemoryMatch> recall(String query, RecallOptions options);
    
    /**
     * 主动检索（session 开始时调用，注入 system prompt）
     *
     * @param taskDescription 任务描述
     * @param options         检索选项
     * @return 格式化的记忆上下文字符串
     */
    String proactiveRecall(String taskDescription, RecallOptions options);
    
    /**
     * 遗忘
     */
    void forget(String memoryId);
    
    /**
     * 执行记忆整合
     */
    ConsolidationReport consolidate(String tenantId, String userId);
}
