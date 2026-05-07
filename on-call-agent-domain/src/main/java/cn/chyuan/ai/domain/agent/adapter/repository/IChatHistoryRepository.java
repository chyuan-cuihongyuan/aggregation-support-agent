package cn.chyuan.ai.domain.agent.adapter.repository;

import cn.chyuan.ai.domain.agent.model.entity.ChatHistoryEntity;

import java.util.List;

public interface IChatHistoryRepository {

    void save(ChatHistoryEntity entity);

    List<ChatHistoryEntity> queryByUserId(String userId);

    void deleteByUserId(String userId);
}
