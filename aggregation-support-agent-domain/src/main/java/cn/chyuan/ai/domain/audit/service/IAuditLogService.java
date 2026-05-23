package cn.chyuan.ai.domain.audit.service;

import cn.chyuan.ai.domain.audit.model.entity.AuditLogEntity;
import cn.chyuan.ai.domain.audit.model.valobj.AuditQueryVO;
import cn.chyuan.ai.domain.audit.model.valobj.AuditStatVO;
import cn.chyuan.ai.types.enums.AuditAction;
import cn.chyuan.ai.types.enums.AuditResult;

import java.util.List;

/**
 * 审计日志服务 — 关键操作流水写入与查询
 * <p>
 * 写入接口默认走异步线程池，落库失败不影响主链路；查询接口仅暴露给管理员。
 * <p>
 * 为避免 domain 层依赖 servlet API，IP/UA 由调用方（trigger 层）从 HttpServletRequest 中
 * 通过 {@code AuditContextSupport} 提取后以字符串形式传入。
 */
public interface IAuditLogService {

    /**
     * 异步写一条审计日志
     *
     * @param userId       操作者 ID（未登录传 0）
     * @param username     操作者用户名（冗余字段，可空）
     * @param action       动作类型
     * @param resourceType 资源类型，可为空字符串
     * @param resourceId   资源标识，可为空字符串
     * @param result       结果
     * @param traceId      追踪 ID，可为空字符串
     * @param detail       详情，可为空字符串
     * @param ipAddress    客户端 IP，可为空字符串
     * @param userAgent    客户端 UA，可为空字符串
     */
    void recordAsync(Long userId,
                     String username,
                     AuditAction action,
                     String resourceType,
                     String resourceId,
                     AuditResult result,
                     String traceId,
                     String detail,
                     String ipAddress,
                     String userAgent);

    /** 管理员视角分页查询 */
    List<AuditLogEntity> queryByCondition(AuditQueryVO query);

    /** 管理员视角总数 */
    long countByCondition(AuditQueryVO query);

    /** 按 action 聚合 */
    List<AuditStatVO> statByAction(AuditQueryVO query);

    /** 按 user 聚合 */
    List<AuditStatVO> statByUser(AuditQueryVO query);
}
