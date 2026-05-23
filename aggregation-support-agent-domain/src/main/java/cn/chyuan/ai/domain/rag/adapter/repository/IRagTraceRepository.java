package cn.chyuan.ai.domain.rag.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.model.entity.RagTraceEntity;
import cn.chyuan.ai.domain.rag.model.valobj.RagTraceAdminQueryVO;
import cn.chyuan.ai.domain.rag.model.valobj.RagTraceStatVO;

import java.util.List;

/**
 * RAG 检索追踪仓储
 */
public interface IRagTraceRepository {

    /** 写入一次检索追踪记录（实现可异步） */
    void save(RagTraceEntity entity);

    /** 按 traceId + 租户作用域查询单条记录 */
    RagTraceEntity queryByTraceId(String traceId, TenantScopeVO scope);

    /** 按 sessionId + 租户作用域查询会话内所有检索记录（按创建时间升序） */
    List<RagTraceEntity> queryBySessionId(String sessionId, TenantScopeVO scope);

    /** 管理员视角分页查询（不受租户作用域限制，调用方需校验 admin 角色） */
    List<RagTraceEntity> queryForAdmin(RagTraceAdminQueryVO query);

    /** 管理员视角总数（不受租户作用域限制，调用方需校验 admin 角色） */
    long countForAdmin(RagTraceAdminQueryVO query);

    /** 按 agentId 聚合：{agentId, total, avgTopk} */
    List<RagTraceStatVO> statByAgent(RagTraceAdminQueryVO query);

    /** 按 ownerUserId 聚合 */
    List<RagTraceStatVO> statByUser(RagTraceAdminQueryVO query);

    /** 按日期（yyyy-MM-dd）聚合 */
    List<RagTraceStatVO> statByDay(RagTraceAdminQueryVO query);
}
