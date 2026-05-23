package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.rag.adapter.repository.IRagTraceRepository;
import cn.chyuan.ai.domain.rag.model.entity.RagTraceEntity;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * RAG 检索追踪控制器 — 提供按 traceId 查询单次检索证据链的能力，
 * 用于前端"溯源"面板与人工审计场景。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag_trace")
public class RagTraceController {

    @Resource
    private IRagTraceRepository ragTraceRepository;

    /**
     * 按 traceId 查询检索追踪记录
     * <p>
     * 强制走当前请求上下文的租户作用域，确保跨租户数据不会被越权读取。
     *
     * @param traceId 检索追踪 ID
     * @return 检索追踪记录；上下文缺失或记录不存在时返回失败响应
     */
    @RequestMapping(value = "/{traceId}", method = RequestMethod.GET)
    public Response<RagTraceEntity> queryByTraceId(@PathVariable("traceId") String traceId) {
        // 取当前请求上下文中的租户作用域，由 AuthFilter 在请求进入时写入
        TenantScopeVO scope = RequestScopeContext.get();
        if (scope == null) {
            log.warn("查询 RAG trace 缺失用户上下文 traceId:{}", traceId);
            return Response.<RagTraceEntity>builder()
                    .code(ResponseCode.AUTH_TOKEN_INVALID.getCode())
                    .info("缺失用户上下文")
                    .build();
        }

        try {
            RagTraceEntity entity = ragTraceRepository.queryByTraceId(traceId, scope);
            if (entity == null) {
                return Response.<RagTraceEntity>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("未找到检索记录")
                        .build();
            }
            return Response.<RagTraceEntity>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(entity)
                    .build();
        } catch (Exception e) {
            log.error("查询 RAG trace 失败 traceId:{}", traceId, e);
            return Response.<RagTraceEntity>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

}
