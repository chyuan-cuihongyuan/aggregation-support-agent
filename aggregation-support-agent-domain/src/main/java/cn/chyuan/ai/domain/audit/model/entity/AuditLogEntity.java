package cn.chyuan.ai.domain.audit.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 审计日志实体 — 记录关键操作流水
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuditLogEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 操作者用户 ID（未登录写 0） */
    private Long userId;

    /** 操作者用户名（冗余字段，便于阅读审计） */
    private String username;

    /** 动作类型，参见 {@link cn.chyuan.ai.types.enums.AuditAction} */
    private String action;

    /** 资源类型：USER/DOCUMENT/SESSION 等 */
    private String resourceType;

    /** 资源标识 */
    private String resourceId;

    /** 结果，SUCCESS / FAILURE */
    private String result;

    /** 请求追踪 ID */
    private String traceId;

    /** 详情（错误信息或变更摘要，最长 1024 字） */
    private String detail;

    /** 客户端 IP */
    private String ipAddress;

    /** 客户端 UA */
    private String userAgent;

    private Date createTime;
}
