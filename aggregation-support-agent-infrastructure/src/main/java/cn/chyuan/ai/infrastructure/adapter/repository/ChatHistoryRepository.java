package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import cn.chyuan.ai.domain.agent.model.entity.ChatHistoryEntity;
import cn.chyuan.ai.domain.agent.model.entity.ChatSessionEntity;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.infrastructure.dao.po.ChatHistoryPO;
import cn.chyuan.ai.infrastructure.dao.po.ChatSessionPO;
import cn.chyuan.ai.infrastructure.persistent.mapper.ChatHistoryMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class ChatHistoryRepository implements IChatHistoryRepository {

    @Resource
    private ChatHistoryMapper chatHistoryMapper;

    @Override
    public void save(ChatHistoryEntity entity) {
        Date now = new Date();
        ChatHistoryPO po = ChatHistoryPO.builder()
                .tenantId(entity.getTenantId())
                .ownerUserId(entity.getOwnerUserId())
                .userId(entity.getUserId())
                .agentId(entity.getAgentId())
                .agentName(entity.getAgentName())
                .sessionId(entity.getSessionId())
                .question(entity.getQuestion())
                .answer(entity.getAnswer())
                .traceId(entity.getTraceId())
                .promptTokens(entity.getPromptTokens() != null ? entity.getPromptTokens() : 0)
                .completionTokens(entity.getCompletionTokens() != null ? entity.getCompletionTokens() : 0)
                .createTime(now)
                .updateTime(now)
                .build();
        chatHistoryMapper.insert(po);
    }

    @Override
    public List<ChatHistoryEntity> queryByScope(TenantScopeVO scope) {
        List<ChatHistoryPO> poList = chatHistoryMapper.queryByScope(scope);
        return poList.stream().map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public ChatHistoryEntity queryById(Long id) {
        ChatHistoryPO po = chatHistoryMapper.queryById(id);
        return po == null ? null : toEntity(po);
    }

    @Override
    public ChatHistoryEntity queryById(Long id, TenantScopeVO scope) {
        ChatHistoryPO po = chatHistoryMapper.queryByIdAndScope(id, scope);
        return po == null ? null : toEntity(po);
    }

    @Override
    public void deleteByScope(TenantScopeVO scope) {
        chatHistoryMapper.deleteByScope(scope);
    }

    @Override
    public void deleteById(Long id) {
        chatHistoryMapper.deleteById(id);
    }

    @Override
    public void deleteById(Long id, TenantScopeVO scope) {
        chatHistoryMapper.deleteByIdAndScope(id, scope);
    }

    @Override
    public void saveSession(ChatSessionEntity entity) {
        Date now = new Date();
        ChatSessionPO po = ChatSessionPO.builder()
                .sessionId(entity.getSessionId())
                .agentId(entity.getAgentId())
                .tenantId(entity.getTenantId())
                .ownerUserId(entity.getOwnerUserId())
                .traceId(entity.getTraceId() != null ? entity.getTraceId() : "")
                .createTime(now)
                .updateTime(now)
                .build();
        chatHistoryMapper.insertSession(po);
    }

    @Override
    public ChatSessionEntity querySession(String sessionId) {
        ChatSessionPO po = chatHistoryMapper.querySession(sessionId);
        return toSessionEntity(po);
    }

    @Override
    public ChatSessionEntity querySession(String sessionId, TenantScopeVO scope) {
        ChatSessionPO po = chatHistoryMapper.querySessionByScope(sessionId, scope);
        return toSessionEntity(po);
    }

    @Override
    public List<ChatHistoryEntity> queryBySessionId(String sessionId, TenantScopeVO scope) {
        List<ChatHistoryPO> poList = chatHistoryMapper.queryBySessionIdAndScope(sessionId, scope);
        return poList.stream().map(this::toEntity).collect(Collectors.toList());
    }

    private ChatHistoryEntity toEntity(ChatHistoryPO po) {
        return ChatHistoryEntity.builder()
                .id(po.getId())
                .tenantId(po.getTenantId())
                .ownerUserId(po.getOwnerUserId())
                .userId(po.getUserId())
                .agentId(po.getAgentId())
                .agentName(po.getAgentName())
                .sessionId(po.getSessionId())
                .question(po.getQuestion())
                .answer(po.getAnswer())
                .traceId(po.getTraceId())
                .promptTokens(po.getPromptTokens())
                .completionTokens(po.getCompletionTokens())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }

    private ChatSessionEntity toSessionEntity(ChatSessionPO po) {
        if (po == null) {
            return null;
        }
        return ChatSessionEntity.builder()
                .id(po.getId())
                .sessionId(po.getSessionId())
                .agentId(po.getAgentId())
                .tenantId(po.getTenantId())
                .ownerUserId(po.getOwnerUserId())
                .traceId(po.getTraceId())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
