package cn.chyuan.ai.api.dto;

import lombok.Data;

/**
 * 租户知识库配额用量 DTO（工单 0168：GET /api/v1/knowledge/quota/{tenantId} 出参）
 */
@Data
public class KnowledgeQuotaDTO {

    /** 租户ID */
    private String tenantId;

    /** 文档数上限（null=不限制） */
    private Long maxDocuments;

    /** 分块数上限（null=不限制） */
    private Long maxChunks;

    /** 当前文档用量（未删除） */
    private Long usedDocuments;

    /** 当前分块用量（未删除文档的分块总数） */
    private Long usedChunks;

    /** 是否仍在配额内 */
    private Boolean withinQuota;
}
