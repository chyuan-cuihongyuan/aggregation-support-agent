package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.rag.adapter.repository.IRagTraceRepository;
import cn.chyuan.ai.domain.rag.model.entity.RagTraceEntity;
import cn.chyuan.ai.domain.rag.model.valobj.RagTraceAdminQueryVO;
import cn.chyuan.ai.domain.rag.model.valobj.RagTraceStatVO;
import cn.chyuan.ai.trigger.annotation.RequireRole;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索追踪控制器 — 提供按 traceId 查询单次检索证据链的能力，
 * 用于前端"溯源"面板与人工审计场景。
 * <p>
 * 普通用户接口（按 traceId 单条查询）强制走 RequestScopeContext 的租户作用域；
 * 管理员接口（/admin/*）由 {@link RequireRole}("admin") 把关，并显式接收 tenantId / ownerUserId / agentId
 * 等过滤入参；当 tenantId 未传时强制使用当前管理员的租户作用域兜底，避免跨租户数据泄漏。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag_trace")
public class RagTraceController {

    /** 单页最大记录数，避免 pageSize 过大引发 OOM/慢查询 */
    private static final int MAX_PAGE_SIZE = 100;

    @Resource
    private IRagTraceRepository ragTraceRepository;

    /**
     * 按 traceId 查询检索追踪记录
     * <p>
     * 强制走当前请求上下文的租户作用域，确保跨租户数据不会被越权读取。
     */
    @RequestMapping(value = "/{traceId}", method = RequestMethod.GET)
    public Response<RagTraceEntity> queryByTraceId(@PathVariable("traceId") String traceId) {
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

    /**
     * 管理员视角分页查询 RAG trace
     * <p>
     * 接受 tenantId 入参；未传时使用当前管理员的租户作用域，避免跨租户数据被越权读取。
     */
    @RequestMapping(value = "/admin/list", method = RequestMethod.GET)
    @RequireRole("admin")
    public Response<Map<String, Object>> adminList(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "startTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(value = "endTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        try {
            int normalizedPage = Math.max(page, 1);
            int normalizedPageSize = clampPageSize(pageSize);
            String effectiveTenantId = resolveTenantId(tenantId);

            RagTraceAdminQueryVO query = RagTraceAdminQueryVO.builder()
                    .tenantId(effectiveTenantId)
                    .ownerUserId(userId)
                    .agentId(agentId)
                    .startTime(startTime)
                    .endTime(endTime)
                    .page(normalizedPage)
                    .pageSize(normalizedPageSize)
                    .build();

            List<RagTraceEntity> list = ragTraceRepository.queryForAdmin(query);
            long total = ragTraceRepository.countForAdmin(query);

            Map<String, Object> data = new HashMap<>();
            data.put("list", list);
            data.put("total", total);
            data.put("page", normalizedPage);
            data.put("pageSize", normalizedPageSize);

            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("管理员查询 RAG trace 列表失败", e);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /** 管理员视角 — 按 agentId 聚合 */
    @RequestMapping(value = "/admin/stats/by_agent", method = RequestMethod.GET)
    @RequireRole("admin")
    public Response<List<RagTraceStatVO>> adminStatByAgent(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "startTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(value = "endTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime) {
        return doStat(ragTraceRepository::statByAgent, tenantId, userId, agentId, startTime, endTime, "by_agent");
    }

    /** 管理员视角 — 按 ownerUserId 聚合 */
    @RequestMapping(value = "/admin/stats/by_user", method = RequestMethod.GET)
    @RequireRole("admin")
    public Response<List<RagTraceStatVO>> adminStatByUser(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "startTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(value = "endTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime) {
        return doStat(ragTraceRepository::statByUser, tenantId, userId, agentId, startTime, endTime, "by_user");
    }

    /** 管理员视角 — 按日期（yyyy-MM-dd）聚合 */
    @RequestMapping(value = "/admin/stats/by_day", method = RequestMethod.GET)
    @RequireRole("admin")
    public Response<List<RagTraceStatVO>> adminStatByDay(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "startTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(value = "endTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime) {
        return doStat(ragTraceRepository::statByDay, tenantId, userId, agentId, startTime, endTime, "by_day");
    }

    /** 通用聚合执行封装：构造 query 时透传全部过滤字段，并兜底租户作用域 */
    private Response<List<RagTraceStatVO>> doStat(
            java.util.function.Function<RagTraceAdminQueryVO, List<RagTraceStatVO>> fn,
            String tenantId,
            String userId,
            String agentId,
            Date startTime,
            Date endTime,
            String tag) {
        try {
            RagTraceAdminQueryVO query = RagTraceAdminQueryVO.builder()
                    .tenantId(resolveTenantId(tenantId))
                    .ownerUserId(userId)
                    .agentId(agentId)
                    .startTime(startTime)
                    .endTime(endTime)
                    .build();
            List<RagTraceStatVO> rows = fn.apply(query);
            return Response.<List<RagTraceStatVO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(rows == null ? Collections.emptyList() : rows)
                    .build();
        } catch (Exception e) {
            log.error("管理员 RAG trace 聚合失败 tag={}", tag, e);
            return Response.<List<RagTraceStatVO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 解析 tenantId：传入非空时直接使用；否则降级到当前管理员的租户作用域，避免跨租户泄漏。
     * 当前上下文也缺失时返回 null，由仓储层走全租户查询（这种情况要求 admin 是超级管理员）。
     */
    private String resolveTenantId(String tenantId) {
        if (tenantId != null && !tenantId.isEmpty()) {
            return tenantId;
        }
        TenantScopeVO scope = RequestScopeContext.get();
        return scope != null ? scope.getTenantId() : null;
    }

    private int clampPageSize(int pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
