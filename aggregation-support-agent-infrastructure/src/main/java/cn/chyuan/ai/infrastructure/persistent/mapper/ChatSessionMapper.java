package cn.chyuan.ai.infrastructure.persistent.mapper;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.infrastructure.dao.po.ChatSessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChatSessionMapper {

    void insert(ChatSessionPO record);

    /** 仅管理员可用：不带租户隔离，普通业务请使用 queryBySessionIdAndScope */
    ChatSessionPO adminQueryBySessionId(@Param("sessionId") String sessionId);

    ChatSessionPO queryBySessionIdAndScope(@Param("sessionId") String sessionId,
                                           @Param("scope") TenantScopeVO scope);
}
