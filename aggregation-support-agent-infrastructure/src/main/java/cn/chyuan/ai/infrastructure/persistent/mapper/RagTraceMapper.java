package cn.chyuan.ai.infrastructure.persistent.mapper;

import cn.chyuan.ai.domain.rag.model.valobj.RagTraceAdminQueryVO;
import cn.chyuan.ai.domain.rag.model.valobj.RagTraceStatVO;
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

    /** 管理员视角分页查询（不带租户作用域） */
    List<RagTracePO> selectForAdmin(@Param("q") RagTraceAdminQueryVO query);

    /** 管理员视角总数 */
    long countForAdmin(@Param("q") RagTraceAdminQueryVO query);

    /** 按 agentId 聚合 */
    List<RagTraceStatVO> statByAgent(@Param("q") RagTraceAdminQueryVO query);

    /** 按 ownerUserId 聚合 */
    List<RagTraceStatVO> statByUser(@Param("q") RagTraceAdminQueryVO query);

    /** 按日期聚合 */
    List<RagTraceStatVO> statByDay(@Param("q") RagTraceAdminQueryVO query);
}
