package cn.chyuan.ai.infrastructure.persistent.mapper;

import cn.chyuan.ai.infrastructure.dao.po.TenantKnowledgeQuotaPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 租户知识库配额 Mapper（工单 0168）
 * <p>
 * 配额 CRUD + 用量统计（统计跨读 document_metadata：deleted_flag=0 口径）；
 * 语句双方言通用（无专有函数，见 tenant_knowledge_quota_mapper.xml）。
 */
@Mapper
public interface TenantKnowledgeQuotaMapper {

    /** 按租户查询配额配置（无行返回 null=不限制） */
    TenantKnowledgeQuotaPO selectByTenantId(@Param("tenantId") String tenantId);

    /** 新增租户配额配置 */
    int insert(TenantKnowledgeQuotaPO record);

    /** 更新租户配额（update_time 应用层维护） */
    int updateQuota(TenantKnowledgeQuotaPO record);

    /** 当前文档用量（tenant_id 匹配且未删除） */
    long countDocumentsByTenant(@Param("tenantId") String tenantId);

    /** 当前分块用量（tenant_id 匹配且未删除文档的 total_chunks 总和） */
    long sumChunksByTenant(@Param("tenantId") String tenantId);
}
