package cn.chyuan.ai.domain.knowledgebase.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 租户配额配置值对象（工单 0168）
 * <p>
 * maxDocuments / maxChunks 为 null 表示该维度不限制；
 * 表中无对应租户行时仓储返回 null（未配置租户 = 不限制兼容）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TenantQuotaVO {

    /** 租户ID */
    private String tenantId;

    /** 文档数上限（null=不限制） */
    private Long maxDocuments;

    /** 分块数上限（null=不限制） */
    private Long maxChunks;
}
