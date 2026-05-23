package cn.chyuan.ai.infrastructure.persistent.mapper;

import cn.chyuan.ai.infrastructure.dao.po.RagTracePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * RAG 检索追踪记录 Mapper
 */
@Mapper
public interface RagTraceMapper {

    /** 插入一条追踪记录 */
    int insert(RagTracePO record);

    /** 按 traceId + 租户作用域查询单条 */
    RagTracePO selectByTraceId(@Param("traceId") String traceId,
                               @Param("tenantId") String tenantId,
                               @Param("ownerUserId") String ownerUserId);

    /** 按 sessionId + 租户作用域查询会话内全部追踪记录（按创建时间升序） */
    List<RagTracePO> selectBySessionId(@Param("sessionId") String sessionId,
                                       @Param("tenantId") String tenantId,
                                       @Param("ownerUserId") String ownerUserId);
}
