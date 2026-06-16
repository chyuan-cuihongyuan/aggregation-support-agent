package cn.chyuan.ai.domain.memory.shortterm.strategy;

import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryOptions;
import cn.chyuan.ai.domain.memory.shortterm.MemoryMessage;
import cn.chyuan.ai.domain.memory.service.AgentMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 卸载策略
 * 
 * 将不常用但重要的信息先存到长期记忆里，从 context window 中移除。
 * 相当于给工作台配了一个「抽屉」，桌面放不下的东西先收到抽屉里，
 * 要用的时候再拿出来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OffloadingStrategy {
    
    private final AgentMemoryService memoryService;
    
    @Value("${agent.memory.shortterm.offload-threshold:25}")
    private int offloadThreshold;
    
    @Value("${agent.memory.shortterm.keep-recent:10}")
    private int keepRecent;
    
    /**
     * 应用卸载策略
     *
     * @param messages  原始消息列表
     * @param tenantId  租户ID
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 卸载后的消息列表
     */
    public List<MemoryMessage> apply(List<MemoryMessage> messages,
                                      String tenantId, String userId, String sessionId) {
        if (messages.size() <= offloadThreshold) {
            return messages;
        }
        
        // 分离 system 消息
        List<MemoryMessage> systemMessages = messages.stream()
            .filter(msg -> "system".equals(msg.getRole()))
            .toList();
        
        List<MemoryMessage> nonSystemMessages = messages.stream()
            .filter(msg -> !"system".equals(msg.getRole()))
            .toList();
        
        // 需要卸载的部分
        int offloadEnd = nonSystemMessages.size() - keepRecent;
        List<MemoryMessage> toOffload = nonSystemMessages.subList(0, offloadEnd);
        List<MemoryMessage> toKeep = nonSystemMessages.subList(offloadEnd, nonSystemMessages.size());
        
        // 异步卸载到长期记忆
        for (MemoryMessage msg : toOffload) {
            try {
                memoryService.remember(
                    msg.getRole() + ": " + msg.getContent(),
                    MemoryOptions.builder()
                        .tenantId(tenantId)
                        .userId(userId)
                        .agentId(null)
                        .sessionId(sessionId)
                        .memoryType(MemoryType.EPISODE)
                        .scope("/conversation/" + sessionId + "/offloaded")
                        .source("context-offloading")
                        .build()
                );
            } catch (Exception e) {
                log.warn("卸载记忆到长期存储失败: {}", e.getMessage());
            }
        }
        
        // 构建结果
        List<MemoryMessage> result = new ArrayList<>(systemMessages);
        result.addAll(toKeep);
        
        log.info("卸载策略: 原始{}条, 卸载{}条到长期记忆, 保留{}条",
            messages.size(), toOffload.size(), toKeep.size());
        
        return result;
    }
}
