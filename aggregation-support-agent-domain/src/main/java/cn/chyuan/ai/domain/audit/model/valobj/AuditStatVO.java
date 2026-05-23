package cn.chyuan.ai.domain.audit.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审计日志聚合统计行 — 按 action 或按用户分组
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuditStatVO {

    /** 分组键（action 名或 userId 字符串） */
    private String groupKey;

    /** 该分组下的总数 */
    private Long total;

    /** 该分组下成功数 */
    private Long successCount;

    /** 该分组下失败数 */
    private Long failureCount;
}
