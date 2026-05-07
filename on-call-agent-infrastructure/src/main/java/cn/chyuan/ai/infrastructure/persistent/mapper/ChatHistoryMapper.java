package cn.chyuan.ai.infrastructure.persistent.mapper;

import cn.chyuan.ai.infrastructure.dao.po.ChatHistoryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatHistoryMapper {

    void insert(ChatHistoryPO record);

    List<ChatHistoryPO> queryByUserId(@Param("userId") String userId);

    void deleteByUserId(@Param("userId") String userId);
}
