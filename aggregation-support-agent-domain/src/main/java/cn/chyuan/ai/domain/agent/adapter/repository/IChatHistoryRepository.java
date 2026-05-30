package cn.chyuan.ai.domain.agent.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.agent.model.entity.ChatHistoryEntity;
import cn.chyuan.ai.domain.agent.model.entity.ChatSessionEntity;

import java.util.List;

public interface IChatHistoryRepository {

    void save(ChatHistoryEntity entity);

    List<ChatHistoryEntity> queryByScope(TenantScopeVO scope);

    /** 仅管理员可用：不带租户隔离，普通业务请使用 queryById(Long, TenantScopeVO) */
    ChatHistoryEntity adminQueryById(Long id);

    ChatHistoryEntity queryById(Long id, TenantScopeVO scope);

    void deleteByScope(TenantScopeVO scope);

    /** 仅管理员可用：不带租户隔离，普通业务请使用 deleteById(Long, TenantScopeVO) */
    void adminDeleteById(Long id);

    void deleteById(Long id, TenantScopeVO scope);

    void saveSession(ChatSessionEntity entity);

    /** 仅管理员可用：不带租户隔离，普通业务请使用 querySession(String, TenantScopeVO) */
    ChatSessionEntity adminQuerySession(String sessionId);

    ChatSessionEntity querySession(String sessionId, TenantScopeVO scope);
}
