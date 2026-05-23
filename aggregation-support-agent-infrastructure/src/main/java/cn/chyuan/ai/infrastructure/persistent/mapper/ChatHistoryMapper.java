package cn.chyuan.ai.infrastructure.persistent.mapper;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.infrastructure.dao.po.ChatHistoryPO;
import cn.chyuan.ai.infrastructure.dao.po.ChatSessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatHistoryMapper {

    void insert(ChatHistoryPO record);

    List<ChatHistoryPO> queryByScope(@Param("scope") TenantScopeVO scope);

    ChatHistoryPO queryById(@Param("id") Long id);

    ChatHistoryPO queryByIdAndScope(@Param("id") Long id, @Param("scope") TenantScopeVO scope);

    void deleteByScope(@Param("scope") TenantScopeVO scope);

    void deleteById(@Param("id") Long id);

    void deleteByIdAndScope(@Param("id") Long id, @Param("scope") TenantScopeVO scope);

    void insertSession(ChatSessionPO record);

    ChatSessionPO querySession(@Param("sessionId") String sessionId);

    ChatSessionPO querySessionByScope(@Param("sessionId") String sessionId,
                                      @Param("scope") TenantScopeVO scope);
}
