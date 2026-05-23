package cn.chyuan.ai.domain.audit.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 审计日志查询条件 — 管理员视角，所有字段均可为空（表示不过滤）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuditQueryVO {

    private Long userId;
    private String action;
    private String resourceType;
    private String result;
    private Date startTime;
    private Date endTime;

    /** 1-based 页码 */
    private Integer page;
    private Integer pageSize;
}
