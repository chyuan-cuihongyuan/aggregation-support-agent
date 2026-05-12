package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import cn.chyuan.ai.domain.agent.model.entity.ChatHistoryEntity;
import cn.chyuan.ai.infrastructure.dao.po.ChatHistoryPO;
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
                .userId(entity.getUserId())
                .agentId(entity.getAgentId())
                .agentName(entity.getAgentName())
                .sessionId(entity.getSessionId())
                .question(entity.getQuestion())
                .answer(entity.getAnswer())
                .createTime(now)
                .updateTime(now)
                .build();
        chatHistoryMapper.insert(po);
    }

    @Override
    public List<ChatHistoryEntity> queryByUserId(String userId) {
        List<ChatHistoryPO> poList = chatHistoryMapper.queryByUserId(userId);
        return poList.stream().map(po -> ChatHistoryEntity.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .agentId(po.getAgentId())
                .agentName(po.getAgentName())
                .sessionId(po.getSessionId())
                .question(po.getQuestion())
                .answer(po.getAnswer())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build()).collect(Collectors.toList());
    }

    @Override
    public void deleteByUserId(String userId) {
        chatHistoryMapper.deleteByUserId(userId);
    }
}
