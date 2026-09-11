package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.knowledgebase.adapter.repository.IKnowledgeQuotaRepository;
import cn.chyuan.ai.domain.knowledgebase.model.valobj.TenantQuotaVO;
import cn.chyuan.ai.infrastructure.dao.po.TenantKnowledgeQuotaPO;
import cn.chyuan.ai.infrastructure.persistent.mapper.TenantKnowledgeQuotaMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

/**
 * 租户配额仓储实现（工单 0168）— tenant_knowledge_quota 表（双方言 DDL 第 11 表）
 */
@Slf4j
@Repository
public class KnowledgeQuotaRepository implements IKnowledgeQuotaRepository {

    @Resource
    private TenantKnowledgeQuotaMapper tenantKnowledgeQuotaMapper;

    @Override
    public TenantQuotaVO quotaOf(String tenantId) {
        TenantKnowledgeQuotaPO po = tenantKnowledgeQuotaMapper.selectByTenantId(tenantId);
        if (po == null) {
            // 未配置租户 = 不限制（存量兼容）
            return null;
        }
        return TenantQuotaVO.builder()
                .tenantId(po.getTenantId())
                .maxDocuments(po.getMaxDocuments() != null ? po.getMaxDocuments().longValue() : null)
                .maxChunks(po.getMaxChunks() != null ? po.getMaxChunks().longValue() : null)
                .build();
    }

    @Override
    public long countDocuments(String tenantId) {
        return tenantKnowledgeQuotaMapper.countDocumentsByTenant(tenantId);
    }

    @Override
    public long sumChunks(String tenantId) {
        return tenantKnowledgeQuotaMapper.sumChunksByTenant(tenantId);
    }
}
