package cn.chyuan.ai.domain.knowledgebase.service;

import cn.chyuan.ai.domain.knowledgebase.QuotaExceededException;
import cn.chyuan.ai.domain.knowledgebase.adapter.repository.IKnowledgeQuotaRepository;
import cn.chyuan.ai.domain.knowledgebase.model.valobj.QuotaUsageVO;
import cn.chyuan.ai.domain.knowledgebase.model.valobj.TenantQuotaVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 租户知识库配额服务单测（工单 0168）
 * <p>
 * 覆盖验收：配额判定（未配置放行 / 恰等于上限边界 / 超限拒绝 / null 上限不限制）
 */
class KnowledgeQuotaServiceTest {

    private IKnowledgeQuotaRepository repository;

    private KnowledgeQuotaService service;

    @BeforeEach
    void setUp() {
        repository = mock(IKnowledgeQuotaRepository.class);
        service = new KnowledgeQuotaService(repository);
    }

    @Test
    void unconfiguredTenantIsAllowed() {
        // 未配置租户（表中无行）：放行（存量兼容）
        when(repository.quotaOf("t-none")).thenReturn(null);
        when(repository.countDocuments("t-none")).thenReturn(9999L);
        when(repository.sumChunks("t-none")).thenReturn(99999L);

        assertThatCode(() -> service.checkDocumentQuota("t-none")).doesNotThrowAnyException();
        assertThatCode(() -> service.checkChunkQuota("t-none", 500)).doesNotThrowAnyException();
    }

    @Test
    void nullLimitMeansUnlimited() {
        // 行内上限为 null：该维度不限制
        when(repository.quotaOf("t-null")).thenReturn(TenantQuotaVO.builder()
                .tenantId("t-null").maxDocuments(null).maxChunks(null).build());
        when(repository.countDocuments("t-null")).thenReturn(1000L);
        when(repository.sumChunks("t-null")).thenReturn(100000L);

        assertThatCode(() -> service.checkDocumentQuota("t-null")).doesNotThrowAnyException();
        assertThatCode(() -> service.checkChunkQuota("t-null", 1000)).doesNotThrowAnyException();
    }

    @Test
    void documentAtExactLimitBoundaryIsRejected() {
        // 恰等于上限边界：已用 10 / 上限 10 → 再入 1 份文档被拒
        when(repository.quotaOf("t-doc")).thenReturn(TenantQuotaVO.builder()
                .tenantId("t-doc").maxDocuments(10L).build());
        when(repository.countDocuments("t-doc")).thenReturn(10L);

        assertThatThrownBy(() -> service.checkDocumentQuota("t-doc"))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("文档数量超出配额");
    }

    @Test
    void documentBelowLimitIsAllowed() {
        // 已用 9 / 上限 10 → 再入 1 份恰好填满，允许
        when(repository.quotaOf("t-doc")).thenReturn(TenantQuotaVO.builder()
                .tenantId("t-doc").maxDocuments(10L).build());
        when(repository.countDocuments("t-doc")).thenReturn(9L);

        assertThatCode(() -> service.checkDocumentQuota("t-doc")).doesNotThrowAnyException();
    }

    @Test
    void chunkQuotaAllowsExactFillAndRejectsOverflow() {
        // 分块边界：已用 90 / 上限 100 → 新增 10 恰好填满允许；新增 11 溢出拒绝
        when(repository.quotaOf("t-chunk")).thenReturn(TenantQuotaVO.builder()
                .tenantId("t-chunk").maxChunks(100L).build());
        when(repository.sumChunks("t-chunk")).thenReturn(90L);

        assertThatCode(() -> service.checkChunkQuota("t-chunk", 10)).doesNotThrowAnyException();

        when(repository.quotaOf("t-chunk")).thenReturn(TenantQuotaVO.builder()
                .tenantId("t-chunk").maxChunks(100L).build());
        assertThatThrownBy(() -> service.checkChunkQuota("t-chunk", 11))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("分块数量超出配额");
    }

    @Test
    void decidePureFunctionBoundaryMatrix() {
        // 判定纯函数边界矩阵
        assertThat(KnowledgeQuotaService.decide(null, 100, 100).allowed()).isTrue();
        assertThat(KnowledgeQuotaService.decide(10L, 9, 1).allowed()).isTrue();
        assertThat(KnowledgeQuotaService.decide(10L, 10, 0).allowed()).isTrue();
        assertThat(KnowledgeQuotaService.decide(10L, 10, 1).allowed()).isFalse();
        assertThat(KnowledgeQuotaService.decide(10L, 11, 0).allowed()).isFalse();
    }

    @Test
    void usageReportsQuotaAndUsed() {
        // 统计：返回上限与用量；超限状态 withinQuota=false
        when(repository.quotaOf("t-usage")).thenReturn(TenantQuotaVO.builder()
                .tenantId("t-usage").maxDocuments(5L).maxChunks(null).build());
        when(repository.countDocuments("t-usage")).thenReturn(6L);
        when(repository.sumChunks("t-usage")).thenReturn(120L);

        QuotaUsageVO usage = service.usage("t-usage");

        assertThat(usage.getMaxDocuments()).isEqualTo(5L);
        assertThat(usage.getMaxChunks()).isNull();
        assertThat(usage.getUsedDocuments()).isEqualTo(6L);
        assertThat(usage.getUsedChunks()).isEqualTo(120L);
        assertThat(usage.isWithinQuota()).isFalse();
    }

    @Test
    void zeroIncomingChunksShortCircuits() {
        // 新增 0 分块：不触发查询直接放行
        when(repository.quotaOf("t-doc")).thenReturn(TenantQuotaVO.builder()
                .tenantId("t-doc").maxChunks(1L).build());
        assertThatCode(() -> service.checkChunkQuota("t-doc", 0)).doesNotThrowAnyException();
    }
}
