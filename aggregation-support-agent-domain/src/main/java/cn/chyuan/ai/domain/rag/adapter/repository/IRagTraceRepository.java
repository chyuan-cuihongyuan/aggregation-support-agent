package cn.chyuan.ai.domain.rag.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.model.entity.RagTraceEntity;

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
}
