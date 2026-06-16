package cn.chyuan.ai.domain.memory.multimodal;

import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryOptions;
import cn.chyuan.ai.domain.memory.model.valobj.RecallOptions;
import cn.chyuan.ai.domain.memory.service.AgentMemoryService;
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
    
    @Override
    public void storeMultimodalMemory(MultimodalMemoryItem item, MemoryOptions options) {
        log.info("存储多模态记忆：type={}, content={}", 
                item.getModalityType(), 
                item.getContent().substring(0, Math.min(50, item.getContent().length())));
        
        try {
            String description = generateDescription(item);
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("modality_type", item.getModalityType());
            metadata.put("original_content", item.getContent());
            if (item.getMetadata() != null) {
                metadata.put("multimodal_metadata", item.getMetadata());
            }
            
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
        
        List<MemoryMatch> allMatches = memoryService.recall(query, options);
        
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
            // TODO: 集成 CLIP 或其他视觉语言模型
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
            // TODO: 集成 Whisper 或其他 ASR 服务
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
        
        List<MemoryMatch> allMatches = memoryService.recall("", options);
        
        return allMatches.stream()
            .filter(match -> {
                Map<String, Object> metadata = match.getEntry().getMetadata();
                return metadata != null 
                    && modalityType.equals(metadata.get("modality_type"));
            })
            .collect(Collectors.toList());
    }
    
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
    
    private String extractImageDescription(String image) {
        // TODO: 集成 CLIP 或其他视觉语言模型
        return "图片内容";
    }
    
    private String transcribeAudio(String audio) {
        // TODO: 集成 Whisper 或其他 ASR 服务
        return "音频内容";
    }
}
