package cn.chyuan.ai.domain.memory.shortterm.impl;

import cn.chyuan.ai.domain.memory.shortterm.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 短期记忆服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortTermMemoryServiceImpl implements IShortTermMemoryService {
    
    private final ContextWindowManager contextWindowManager;
    
    @Override
    public void addMessage(MemoryMessage message, String sessionId) {
        contextWindowManager.addMessage(sessionId, message);
    }
    
    @Override
    public List<MemoryMessage> getContextWindow(String sessionId) {
        return getContextWindow(sessionId, ContextWindowStrategy.SLIDING_WINDOW, null, null);
    }
    
    public List<MemoryMessage> getContextWindow(String sessionId, ContextWindowStrategy strategy,
                                                  String tenantId, String userId) {
        return contextWindowManager.getMessages(sessionId, strategy, tenantId, userId);
    }
    
    @Override
    public void applyManagementStrategy(String sessionId, ContextWindowStrategy strategy) {
        log.info("应用 Context Window 管理策略: sessionId={}, strategy={}", sessionId, strategy);
        // 策略会在下次 getContextWindow 时自动应用
    }
    
    @Override
    public void clearSession(String sessionId) {
        contextWindowManager.clearSession(sessionId);
        log.info("清空会话短期记忆: sessionId={}", sessionId);
    }
    
    @Override
    public int estimateTokenUsage(String sessionId) {
        return contextWindowManager.estimateTokenUsage(sessionId);
    }
}
