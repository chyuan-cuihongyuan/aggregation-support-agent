package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 租户知识库配额 PO — 对应 tenant_knowledge_quota 表（工单 0168，双方言 DDL 第 11 表）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TenantKnowledgeQuotaPO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    private Long id;

    /** 租户ID（唯一定位，未配置租户=不限制） */
    private String tenantId;

    /** 文档数上限（NULL=不限制） */
    private Integer maxDocuments;

    /** 分块数上限（NULL=不限制） */
    private Integer maxChunks;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间（应用层维护） */
    private Date updateTime;
}
