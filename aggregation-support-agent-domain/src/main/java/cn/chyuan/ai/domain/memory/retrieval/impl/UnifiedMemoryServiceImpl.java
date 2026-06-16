package cn.chyuan.ai.domain.memory.retrieval.impl;

import cn.chyuan.ai.domain.memory.model.valobj.ConsolidationReport;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryOptions;
import cn.chyuan.ai.domain.memory.model.valobj.RecallOptions;
import cn.chyuan.ai.domain.memory.retrieval.HybridRetrievalService;
import cn.chyuan.ai.domain.memory.retrieval.IUnifiedMemoryService;
import cn.chyuan.ai.domain.memory.service.AgentMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 统一记忆服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnifiedMemoryServiceImpl implements IUnifiedMemoryService {
    
    private final AgentMemoryService memoryService;
    private final HybridRetrievalService hybridRetrievalService;
    
    @Override
    public void remember(String content, MemoryOptions options) {
        memoryService.remember(content, options);
    }
    
    @Override
    public List<MemoryMatch> recall(String query, RecallOptions options) {
        return hybridRetrievalService.hybridRecall(query, options);
    }
    
    @Override
    public String proactiveRecall(String taskDescription, RecallOptions options) {
        List<MemoryMatch> memories = recall(taskDescription, options);
        
        if (memories.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("\n## 相关记忆\n");
        sb.append("以下是与当前任务相关的历史记忆，请参考：\n\n");
        
        for (int i = 0; i < memories.size(); i++) {
            MemoryMatch match = memories.get(i);
            sb.append(String.format(
                "%d. [%.0f%%相关/%s] %s\n",
                i + 1,
                match.getScore() * 100,
                match.getEntry().getMemoryType().name(),
                match.getEntry().getContent()
            ));
        }
        
        return sb.toString();
    }
    
    @Override
    public void forget(String memoryId) {
        memoryService.forget(memoryId);
    }
    
    @Override
    public ConsolidationReport consolidate(String tenantId, String userId) {
        return memoryService.consolidate(tenantId, userId);
    }
}
