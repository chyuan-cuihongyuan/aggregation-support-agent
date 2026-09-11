package cn.chyuan.ai.domain.knowledgebase.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 租户配额用量值对象（工单 0168：统计端点 GET /api/v1/knowledge/quota/{tenantId} 出参内核）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class QuotaUsageVO {

    /** 租户ID */
    private String tenantId;

    /** 文档数上限（null=不限制） */
    private Long maxDocuments;

    /** 分块数上限（null=不限制） */
    private Long maxChunks;

    /** 当前文档用量（未删除） */
    private long usedDocuments;

    /** 当前分块用量（未删除文档的分块总数） */
    private long usedChunks;

    /** 是否仍在配额内（统计口径：当前用量未超限） */
    private boolean withinQuota;
}
