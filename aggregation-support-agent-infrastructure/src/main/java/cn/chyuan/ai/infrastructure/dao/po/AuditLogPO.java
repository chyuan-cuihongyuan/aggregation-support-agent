package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 审计日志 PO — 对应 audit_log 表
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuditLogPO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String username;
    private String action;
    private String resourceType;
    private String resourceId;
    private String result;
    private String traceId;
    private String detail;
    private String ipAddress;
    private String userAgent;
    private Date createTime;
}
