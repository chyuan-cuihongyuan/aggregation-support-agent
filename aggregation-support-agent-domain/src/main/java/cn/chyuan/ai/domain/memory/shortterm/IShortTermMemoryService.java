package cn.chyuan.ai.domain.memory.shortterm;

import java.util.List;

/**
 * 短期记忆服务接口
 * 
 * 短期记忆是 context window 里的 messages 列表，
 * 维持着当前任务执行过程中的完整状态。
 * 任务结束（对话关闭），这块记忆就清空了。
 */
public interface IShortTermMemoryService {
    
    /**
     * 添加消息到短期记忆
     *
     * @param message   消息
     * @param sessionId 会话ID
     */
    void addMessage(MemoryMessage message, String sessionId);
    
    /**
     * 获取当前 Context Window 内的消息列表
     *
     * @param sessionId 会话ID
     * @return 消息列表（已应用管理策略）
     */
    List<MemoryMessage> getContextWindow(String sessionId);
    
    /**
     * 应用 Context Window 管理策略
     *
     * @param sessionId 会话ID
     * @param strategy  策略类型
     */
    void applyManagementStrategy(String sessionId, ContextWindowStrategy strategy);
    
    /**
     * 清空会话的短期记忆
     *
     * @param sessionId 会话ID
     */
    void clearSession(String sessionId);
    
    /**
     * 获取当前 Context Window 的 token 使用量估算
     *
     * @param sessionId 会话ID
     * @return 估算的 token 数量
     */
    int estimateTokenUsage(String sessionId);
}
