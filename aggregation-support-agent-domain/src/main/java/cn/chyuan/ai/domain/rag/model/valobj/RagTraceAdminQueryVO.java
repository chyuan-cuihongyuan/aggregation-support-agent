package cn.chyuan.ai.domain.rag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * RAG 检索追踪 — 管理员视角查询条件
 * <p>
 * 所有字段均可为空（表示不过滤），由 Controller 在 admin 接口构造后下传仓储层。
 * 注意：admin 视角不再受 RequestScopeContext 的租户作用域强制约束，
 * 而是通过 {@code @RequireRole("admin")} 注解把关，并允许显式按 ownerUserId 过滤。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RagTraceAdminQueryVO {

    /** 租户 ID，可空（管理员可跨租户查询） */
    private String tenantId;

    /** 用户 ID，可空（管理员可按用户过滤） */
    private String ownerUserId;

    /** 智能体 ID，可空 */
    private String agentId;

    /** 查询起始时间，可空 */
    private Date startTime;

    /** 查询结束时间，可空 */
    private Date endTime;

    /** 1-based 页码 */
    private Integer page;

    /** 每页条数 */
    private Integer pageSize;
}
