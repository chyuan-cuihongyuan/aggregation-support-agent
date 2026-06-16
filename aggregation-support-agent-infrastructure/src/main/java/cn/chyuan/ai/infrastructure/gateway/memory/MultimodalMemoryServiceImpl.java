package cn.chyuan.ai.infrastructure.gateway.memory;

import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryOptions;
import cn.chyuan.ai.domain.memory.model.valobj.RecallOptions;
import cn.chyuan.ai.domain.memory.multimodal.IMultimodalMemoryService;
import cn.chyuan.ai.domain.memory.multimodal.MultimodalMemoryItem;
import cn.chyuan.ai.domain.memory.service.AgentMemoryService;
import cn.chyuan.ai.infrastructure.adapter.repository.memory.AgentMemoryMilvusRepository;
import cn.chyuan.ai.domain.memory.adapter.port.IEmbeddingPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 多模态记忆服务实现
 * 
 * 支持图片、音频、视频等多模态记忆的存储和检索
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultimodalMemoryServiceImpl implements IMultimodalMemoryService {
    
    private final AgentMemoryService memoryService;
    private final AgentMemoryMilvusRepository milvusRepository;
    private final IEmbeddingPort embeddingPort;
    
    @Override
    public void storeMultimodalMemory(MultimodalMemoryItem item, MemoryOptions options) {
        log.info("存储多模态记忆：type={}, content={}", 
                item.getModalityType(), 
                item.getContent().substring(0, Math.min(50, item.getContent().length())));
        
        try {
            // Step 1: 生成描述文本（用于检索）
            String description = generateDescription(item);
            
            // Step 2: 构建记忆实体
            String memoryId = UUID.randomUUID().toString();
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("modality_type", item.getModalityType());
            metadata.put("original_content", item.getContent());
            if (item.getMetadata() != null) {
                metadata.put("multimodal_metadata", item.getMetadata());
            }
            
            // Step 3: 存储到长期记忆（使用描述文本）
            memoryService.remember(
                description,
                MemoryOptions.builder()
                    .tenantId(options.getTenantId())
                    .userId(options.getUserId())
                    .agentId(options.getAgentId())
                    .sessionId(options.getSessionId())
                    .memoryType(MemoryType.EPISODE)
                    .scope("/multimodal/" + item.getModalityType())
                    .source("multimodal")
                    .metadata(metadata)
                    .build()
            );
            
            log.info("多模态记忆存储成功：memoryId={}", memoryId);
            
        } catch (Exception e) {
            log.error("多模态记忆存储失败", e);
            throw new RuntimeException("多模态记忆存储失败", e);
        }
    }
    
    @Override
    public void storeMultimodalMemoryBatch(List<MultimodalMemoryItem> items, MemoryOptions options) {
        log.info("批量存储多模态记忆：count={}", items.size());
        for (MultimodalMemoryItem item : items) {
            try {
                storeMultimodalMemory(item, options);
            } catch (Exception e) {
                log.error("批量存储中单项失败，继续处理其他项", e);
            }
        }
    }
    
    @Override
    public List<MemoryMatch> recallMultimodalMemory(String query, RecallOptions options) {
        log.info("检索多模态记忆（文本查询）: query={}", query);
        
        // 使用统一的记忆检索服务
        List<MemoryMatch> allMatches = memoryService.recall(query, options);
        
        // 过滤出多模态记忆
        return allMatches.stream()
            .filter(match -> {
                Map<String, Object> metadata = match.getEntry().getMetadata();
                return metadata != null && metadata.containsKey("modality_type");
            })
            .collect(Collectors.toList());
    }
    
    @Override
    public List<MemoryMatch> recallByImage(String image, RecallOptions options) {
        log.info("基于图片检索多模态记忆");
        
        try {
            // TODO: 集成图片 Embedding 模型（如 CLIP）
            // 目前简化为：提取图片描述文本进行检索
            String imageDescription = extractImageDescription(image);
            return recallMultimodalMemory(imageDescription, options);
            
        } catch (Exception e) {
            log.error("图片检索失败", e);
            return List.of();
        }
    }
    
    @Override
    public List<MemoryMatch> recallByAudio(String audio, RecallOptions options) {
        log.info("基于音频检索多模态记忆");
        
        try {
            // TODO: 集成语音识别（ASR）
            // 目前简化为：将音频转为文本后检索
            String audioText = transcribeAudio(audio);
            return recallMultimodalMemory(audioText, options);
            
        } catch (Exception e) {
            log.error("音频检索失败", e);
            return List.of();
        }
    }
    
    @Override
    public void forgetMultimodalMemory(String memoryId) {
        log.info("删除多模态记忆：memoryId={}", memoryId);
        memoryService.forget(memoryId);
    }
    
    @Override
    public List<MemoryMatch> recallByModalityType(String modalityType, RecallOptions options) {
        log.info("按类型检索多模态记忆：type={}", modalityType);
        
        // 先检索所有记忆，然后按类型过滤
        List<MemoryMatch> allMatches = memoryService.recall("", options);
        
        return allMatches.stream()
            .filter(match -> {
                Map<String, Object> metadata = match.getEntry().getMetadata();
                return metadata != null 
                    && modalityType.equals(metadata.get("modality_type"));
            })
            .collect(Collectors.toList());
    }
    
    /**
     * 生成多模态记忆的描述文本
     */
    private String generateDescription(MultimodalMemoryItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(item.getModalityType()).append("] ");
        
        if (item.getMetadata() != null && item.getMetadata().getDescription() != null) {
            sb.append(item.getMetadata().getDescription());
        } else {
            sb.append(item.getContent());
        }
        
        return sb.toString();
    }
    
    /**
     * 提取图片描述（简化实现）
     */
    private String extractImageDescription(String image) {
        // TODO: 集成 CLIP 或其他视觉语言模型
        // 目前返回占位符
        return "图片内容";
    }
    
    /**
     * 音频转文本（简化实现）
     */
    private String transcribeAudio(String audio) {
        // TODO: 集成 Whisper 或其他 ASR 服务
        // 目前返回占位符
        return "音频内容";
    }
}
