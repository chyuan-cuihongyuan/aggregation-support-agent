package cn.chyuan.ai.test.trigger.http;

import cn.chyuan.ai.api.dto.KnowledgeQuotaDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.knowledgebase.model.valobj.QuotaUsageVO;
import cn.chyuan.ai.domain.knowledgebase.service.KnowledgeQuotaService;
import cn.chyuan.ai.trigger.http.KnowledgeQuotaController;
import cn.chyuan.ai.types.enums.ResponseCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * 租户知识库配额统计端点合同测试（工单 0168）
 * <p>
 * 覆盖验收：统计端点合同 — 成功查询 / 未配置租户返回不限制口径 /
 * 越权拒绝 / 配额服务缺席 / 空租户ID 非法参数。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("租户知识库配额端点测试")
public class KnowledgeQuotaControllerTest {

    @Mock
    private KnowledgeQuotaService knowledgeQuotaService;

    @InjectMocks
    private KnowledgeQuotaController controller;

    @AfterEach
    void tearDown() {
        // 清理请求上下文 ThreadLocal，避免用例间污染
        RequestScopeContext.clear();
    }

    @Test
    @DisplayName("成功查询 — 返回配额上限与用量")
    public void testQuota_ReturnsUsage() {
        when(knowledgeQuotaService.usage("t1")).thenReturn(QuotaUsageVO.builder()
                .tenantId("t1").maxDocuments(10L).maxChunks(1000L)
                .usedDocuments(3L).usedChunks(120L).withinQuota(true)
                .build());

        Response<KnowledgeQuotaDTO> response = controller.quota("t1");

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
        assertEquals("t1", response.getData().getTenantId());
        assertEquals(10L, response.getData().getMaxDocuments());
        assertEquals(1000L, response.getData().getMaxChunks());
        assertEquals(3L, response.getData().getUsedDocuments());
        assertEquals(120L, response.getData().getUsedChunks());
        assertEquals(Boolean.TRUE, response.getData().getWithinQuota());
    }

    @Test
    @DisplayName("未配置租户 — 上限为 null 表示不限制")
    public void testQuota_UnconfiguredTenantMeansUnlimited() {
        when(knowledgeQuotaService.usage("t-none")).thenReturn(QuotaUsageVO.builder()
                .tenantId("t-none").maxDocuments(null).maxChunks(null)
                .usedDocuments(7L).usedChunks(88L).withinQuota(true)
                .build());

        Response<KnowledgeQuotaDTO> response = controller.quota("t-none");

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNull(response.getData().getMaxDocuments());
        assertNull(response.getData().getMaxChunks());
    }

    @Test
    @DisplayName("越权拒绝 — 上下文租户与路径租户不一致")
    public void testQuota_CrossTenantDenied() {
        RequestScopeContext.set(TenantScopeVO.singleUser("u1"));
        // 手动改写上下文租户以模拟登录租户与路径不一致
        RequestScopeContext.set(TenantScopeVO.builder().tenantId("other").ownerUserId("u1").build());

        Response<KnowledgeQuotaDTO> response = controller.quota("t1");

        assertEquals(ResponseCode.AUTH_PERMISSION_DENIED.getCode(), response.getCode());
    }

    @Test
    @DisplayName("配额服务缺席 — 返回未错误而非 500")
    public void testQuota_ServiceAbsent() {
        KnowledgeQuotaController bareController = new KnowledgeQuotaController();

        Response<KnowledgeQuotaDTO> response = bareController.quota("t1");

        assertEquals(ResponseCode.UN_ERROR.getCode(), response.getCode());
    }

    @Test
    @DisplayName("空租户ID — 非法参数")
    public void testQuota_BlankTenantId() {
        Response<KnowledgeQuotaDTO> response = controller.quota(" ");

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
    }
}
