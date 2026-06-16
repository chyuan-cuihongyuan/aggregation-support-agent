package cn.chyuan.ai.domain.memory.shortterm;

import cn.chyuan.ai.domain.memory.shortterm.strategy.SlidingWindowStrategy;
import cn.chyuan.ai.domain.memory.shortterm.strategy.SummaryCompressionStrategy;
import cn.chyuan.ai.domain.memory.shortterm.strategy.OffloadingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context Window 管理器
 * 
 * 管理每个会话的短期记忆，支持三种管理策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextWindowManager {
    
    private final SlidingWindowStrategy slidingWindowStrategy;
    private final SummaryCompressionStrategy summaryCompressionStrategy;
    private final OffloadingStrategy offloadingStrategy;
    
    /** 会话消息存储 (sessionId -> messages) */
    private final Map<String, List<MemoryMessage>> sessionMessages = new ConcurrentHashMap<>();
    
    /** 默认最大 token 估算 */
    private static final int DEFAULT_MAX_TOKENS = 8000;
    
    /**
     * 添加消息
     */
    public void addMessage(String sessionId, MemoryMessage message) {
        sessionMessages.computeIfAbsent(sessionId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
            .add(message);
    }
    
    /**
     * 获取消息列表（应用策略后）
     */
    public List<MemoryMessage> getMessages(String sessionId, ContextWindowStrategy strategy,
                                            String tenantId, String userId) {
        List<MemoryMessage> messages = sessionMessages.getOrDefault(sessionId, List.of());
        
        return switch (strategy) {
            case SLIDING_WINDOW -> slidingWindowStrategy.apply(messages);
            case SUMMARY_COMPRESSION -> summaryCompressionStrategy.apply(messages);
            case OFFLOADING -> offloadingStrategy.apply(messages, tenantId, userId, sessionId);
        };
    }
    
    /**
     * 获取原始消息列表
     */
    public List<MemoryMessage> getRawMessages(String sessionId) {
        return sessionMessages.getOrDefault(sessionId, List.of());
    }
    
    /**
     * 清空会话
     */
    public void clearSession(String sessionId) {
        sessionMessages.remove(sessionId);
    }
    
    /**
     * 估算 token 使用量（粗略估算：中文 1 字 ≈ 1.5 token，英文 1 词 ≈ 1 token）
     */
    public int estimateTokenUsage(String sessionId) {
        List<MemoryMessage> messages = sessionMessages.getOrDefault(sessionId, List.of());
        int totalChars = messages.stream()
            .mapToInt(msg -> msg.getContent() != null ? msg.getContent().length() : 0)
            .sum();
        return (int) (totalChars * 1.5);
    }
}
