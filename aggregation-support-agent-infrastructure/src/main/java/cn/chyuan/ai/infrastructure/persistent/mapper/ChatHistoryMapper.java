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

    /** 仅管理员可用：不带租户隔离，普通业务请使用 queryByIdAndScope */
    ChatHistoryPO adminQueryById(@Param("id") Long id);

    ChatHistoryPO queryByIdAndScope(@Param("id") Long id, @Param("scope") TenantScopeVO scope);

    void deleteByScope(@Param("scope") TenantScopeVO scope);

    /** 仅管理员可用：不带租户隔离，普通业务请使用 deleteByIdAndScope */
    void adminDeleteById(@Param("id") Long id);

    void deleteByIdAndScope(@Param("id") Long id, @Param("scope") TenantScopeVO scope);

    void insertSession(ChatSessionPO record);

    /** 仅管理员可用：不带租户隔离，普通业务请使用 querySessionByScope */
    ChatSessionPO adminQuerySession(@Param("sessionId") String sessionId);

    ChatSessionPO querySessionByScope(@Param("sessionId") String sessionId,
                                      @Param("scope") TenantScopeVO scope);
}
