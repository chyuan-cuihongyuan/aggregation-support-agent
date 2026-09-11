package cn.chyuan.ai.domain.knowledgebase.adapter.repository;

import cn.chyuan.ai.domain.knowledgebase.model.valobj.TenantQuotaVO;

/**
 * 租户配额仓储端口（工单 0168）
 * <p>
 * domain 端口：infrastructure 经 tenant_knowledge_quota 表实现（双方言 DDL）；
 * 用量统计复用 document_metadata 表口径（deleted_flag=0）。
 */
public interface IKnowledgeQuotaRepository {

    /**
     * 查询租户配额配置
     *
     * @param tenantId 租户ID
     * @return 配额配置；表中无该租户行返回 null（未配置=不限制）
     */
    TenantQuotaVO quotaOf(String tenantId);

    /**
     * 当前文档用量（tenant_id 匹配且 deleted_flag=0 的文档数）
     */
    long countDocuments(String tenantId);

    /**
     * 当前分块用量（tenant_id 匹配且 deleted_flag=0 文档的 total_chunks 总和）
     */
    long sumChunks(String tenantId);
}
