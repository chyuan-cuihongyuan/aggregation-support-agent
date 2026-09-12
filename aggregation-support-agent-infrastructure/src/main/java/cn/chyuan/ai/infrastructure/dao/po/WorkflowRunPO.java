package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 工作流运行表 PO（工单 0212 AB9）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRunPO implements Serializable {

    private Long id;

    private String runId;

    private String workflowName;

    private Integer workflowVersion;

    private String tenantId;

    private String status;

    private String failedNodeId;

    private String error;

    private Long durationMs;

    /** 节点级明细（JSON 数组文本：nodeId/status/attempts/durationMs/error） */
    private String nodeRunsJson;

    private Date createTime;
}
