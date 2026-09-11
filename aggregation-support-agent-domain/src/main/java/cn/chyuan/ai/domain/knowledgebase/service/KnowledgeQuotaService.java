package cn.chyuan.ai.domain.knowledgebase.service;

import cn.chyuan.ai.domain.knowledgebase.QuotaExceededException;
import cn.chyuan.ai.domain.knowledgebase.adapter.repository.IKnowledgeQuotaRepository;
import cn.chyuan.ai.domain.knowledgebase.model.valobj.QuotaUsageVO;
import cn.chyuan.ai.domain.knowledgebase.model.valobj.TenantQuotaVO;
import org.springframework.stereotype.Service;

/**
 * 租户知识库配额服务（工单 0168，W6）
 * <p>
 * 文档/分块入库前校验 + 用量统计。判定纯函数内核：
 * <ul>
 *   <li>未配置租户（表中无行）→ 不限制放行（存量兼容）</li>
 *   <li>行内上限为 null → 该维度不限制放行</li>
 *   <li>used + incoming &gt; max → 拒绝（恰等于上限边界：used+incoming == max 允许，
 *       即已用量恰好等于上限时再入库被拒）</li>
 * </ul>
 * 超限拒绝抛 {@link QuotaExceededException}（明确错误），不静默截断。
 */
@Service
public class KnowledgeQuotaService {

    /** 判定结果（allowed=false 时 reason 给出可读原因） */
    public record QuotaDecision(boolean allowed, String reason) {
    }

    private final IKnowledgeQuotaRepository repository;

    public KnowledgeQuotaService(IKnowledgeQuotaRepository repository) {
        this.repository = repository;
    }

    /**
     * 文档入库前校验（超限抛 QuotaExceededException）
     */
    public void checkDocumentQuota(String tenantId) {
        TenantQuotaVO quota = repository.quotaOf(tenantId);
        QuotaDecision decision = decide(quota == null ? null : quota.getMaxDocuments(),
                repository.countDocuments(tenantId), 1);
        if (!decision.allowed()) {
            throw new QuotaExceededException(
                    "租户 " + tenantId + " 文档数量超出配额限制：" + decision.reason());
        }
    }

    /**
     * 分块入库前校验（本次上传新增 chunks 计入；超限抛 QuotaExceededException）
     */
    public void checkChunkQuota(String tenantId, int incomingChunks) {
        if (incomingChunks <= 0) {
            return;
        }
        TenantQuotaVO quota = repository.quotaOf(tenantId);
        QuotaDecision decision = decide(quota == null ? null : quota.getMaxChunks(),
                repository.sumChunks(tenantId), incomingChunks);
        if (!decision.allowed()) {
            throw new QuotaExceededException(
                    "租户 " + tenantId + " 分块数量超出配额限制：" + decision.reason());
        }
    }

    /**
     * 用量统计（统计端点出参；未配置租户同样返回用量，上限字段为 null 表示不限制）
     */
    public QuotaUsageVO usage(String tenantId) {
        TenantQuotaVO quota = repository.quotaOf(tenantId);
        Long maxDocuments = quota == null ? null : quota.getMaxDocuments();
        Long maxChunks = quota == null ? null : quota.getMaxChunks();
        long usedDocuments = repository.countDocuments(tenantId);
        long usedChunks = repository.sumChunks(tenantId);
        boolean withinQuota = decide(maxDocuments, usedDocuments, 0).allowed()
                && decide(maxChunks, usedChunks, 0).allowed();
        return QuotaUsageVO.builder()
                .tenantId(tenantId)
                .maxDocuments(maxDocuments)
                .maxChunks(maxChunks)
                .usedDocuments(usedDocuments)
                .usedChunks(usedChunks)
                .withinQuota(withinQuota)
                .build();
    }

    /**
     * 判定纯函数内核
     *
     * @param max      上限（null=不限制）
     * @param used     当前已用量
     * @param incoming 本次拟新增量
     * @return allowed=true 放行；used + incoming <= max 即放行（恰等于上限边界允许）
     */
    public static QuotaDecision decide(Long max, long used, long incoming) {
        if (max == null) {
            // 未配置上限 = 不限制
            return new QuotaDecision(true, "未配置上限，不限制");
        }
        if (used + incoming <= max) {
            return new QuotaDecision(true, "used=" + used + ", incoming=" + incoming + ", max=" + max);
        }
        return new QuotaDecision(false, "已用 " + used + " + 新增 " + incoming + " 超过上限 " + max);
    }
}
