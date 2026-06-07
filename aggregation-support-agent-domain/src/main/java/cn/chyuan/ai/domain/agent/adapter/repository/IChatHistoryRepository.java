package cn.chyuan.ai.domain.agent.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.agent.model.entity.ChatHistoryEntity;
import cn.chyuan.ai.domain.agent.model.entity.ChatSessionEntity;

import java.util.List;

public interface IChatHistoryRepository {

    void save(ChatHistoryEntity entity);

    List<ChatHistoryEntity> queryByScope(TenantScopeVO scope);

    ChatHistoryEntity queryById(Long id);

    ChatHistoryEntity queryById(Long id, TenantScopeVO scope);

    void deleteByScope(TenantScopeVO scope);

    void deleteById(Long id);

    void deleteById(Long id, TenantScopeVO scope);

    void saveSession(ChatSessionEntity entity);

    ChatSessionEntity querySession(String sessionId);

    ChatSessionEntity querySession(String sessionId, TenantScopeVO scope);

    /**
     * 根据 sessionId 查询对话历史（用于会话重建时回灌消息）
     *
     * @param sessionId 会话ID
     * @param scope     租户作用域
     * @return 该会话下的对话历史列表（按时间正序）
     */
    List<ChatHistoryEntity> queryBySessionId(String sessionId, TenantScopeVO scope);
}
