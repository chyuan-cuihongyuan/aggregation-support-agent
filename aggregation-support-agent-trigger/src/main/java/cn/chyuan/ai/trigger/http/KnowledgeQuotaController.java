package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.KnowledgeQuotaDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.knowledgebase.model.valobj.QuotaUsageVO;
import cn.chyuan.ai.domain.knowledgebase.service.KnowledgeQuotaService;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户知识库配额统计端点（工单 0168，W6）
 * <p>
 * GET /api/v1/knowledge/quota/{tenantId}：返回配额上限与当前用量（只读，不改状态）。
 * 越权防护：请求上下文存在且与路径租户不一致时拒绝（上下文缺席=服务间调用放行）。
 * 配额服务缺席（未装配）时返回未错误提示，不抛 500。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/knowledge/quota")
public class KnowledgeQuotaController {

    @Autowired(required = false)
    private KnowledgeQuotaService knowledgeQuotaService;

    @RequestMapping(value = "/{tenantId}", method = RequestMethod.GET)
    public Response<KnowledgeQuotaDTO> quota(@PathVariable("tenantId") String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Response.<KnowledgeQuotaDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("租户ID不能为空")
                    .build();
        }
        // 越权防护：已登录上下文只能查询自己租户的配额
        TenantScopeVO contextScope = RequestScopeContext.get();
        if (contextScope != null && contextScope.getTenantId() != null
                && !contextScope.getTenantId().equals(tenantId)) {
            return Response.<KnowledgeQuotaDTO>builder()
                    .code(ResponseCode.AUTH_PERMISSION_DENIED.getCode())
                    .info("无权查询其他租户的配额")
                    .build();
        }
        if (knowledgeQuotaService == null) {
            return Response.<KnowledgeQuotaDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("配额服务未启用")
                    .build();
        }

        try {
            QuotaUsageVO usage = knowledgeQuotaService.usage(tenantId);
            return Response.<KnowledgeQuotaDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toDTO(usage))
                    .build();
        } catch (Exception e) {
            log.error("查询租户配额失败: tenantId={}", tenantId, e);
            return Response.<KnowledgeQuotaDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("查询失败: " + e.getMessage())
                    .build();
        }
    }

    private KnowledgeQuotaDTO toDTO(QuotaUsageVO usage) {
        KnowledgeQuotaDTO dto = new KnowledgeQuotaDTO();
        dto.setTenantId(usage.getTenantId());
        dto.setMaxDocuments(usage.getMaxDocuments());
        dto.setMaxChunks(usage.getMaxChunks());
        dto.setUsedDocuments(usage.getUsedDocuments());
        dto.setUsedChunks(usage.getUsedChunks());
        dto.setWithinQuota(usage.isWithinQuota());
        return dto;
    }
}
