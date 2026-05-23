package cn.chyuan.ai.domain.audit.adapter.repository;

import cn.chyuan.ai.domain.audit.model.entity.AuditLogEntity;
import cn.chyuan.ai.domain.audit.model.valobj.AuditQueryVO;
import cn.chyuan.ai.domain.audit.model.valobj.AuditStatVO;

import java.util.List;

/**
 * 审计日志仓储 — 管理员视角，不带租户作用域过滤
 */
public interface IAuditLogRepository {

    /** 写入一条审计日志 */
    void save(AuditLogEntity entity);

    /** 多条件分页查询 */
    List<AuditLogEntity> queryByCondition(AuditQueryVO query);

    /** 多条件计数 */
    long countByCondition(AuditQueryVO query);

    /** 按 action 聚合 */
    List<AuditStatVO> statByAction(AuditQueryVO query);

    /** 按 user 聚合 */
    List<AuditStatVO> statByUser(AuditQueryVO query);
}
