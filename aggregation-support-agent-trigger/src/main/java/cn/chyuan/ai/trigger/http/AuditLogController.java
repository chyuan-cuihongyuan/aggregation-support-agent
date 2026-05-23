package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.audit.model.entity.AuditLogEntity;
import cn.chyuan.ai.domain.audit.model.valobj.AuditQueryVO;
import cn.chyuan.ai.domain.audit.model.valobj.AuditStatVO;
import cn.chyuan.ai.domain.audit.service.IAuditLogService;
import cn.chyuan.ai.trigger.annotation.RequireRole;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 审计日志管理员控制器
 * <p>
 * 所有接口均要求 admin 角色，提供审计日志多条件分页查询与按 action / user 聚合统计。
 * 与 RAG trace 管理员接口一致：绕过 RequestScopeContext，由 {@link RequireRole}("admin") 把关。
 * <p>
 * 聚合接口同样接收 userId / action / resourceType / result 等过滤字段，避免"聚合接口给出的统计与
 * 分页接口看到的明细对不上"的体验问题；pageSize 在 controller 层 clamp 到 {@value #MAX_PAGE_SIZE}，
 * 防止恶意大值打爆 DB 与内存。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/audit")
public class AuditLogController {

    /** 单页最大记录数，避免 pageSize 过大引发 OOM/慢查询 */
    private static final int MAX_PAGE_SIZE = 100;

    @Resource
    private IAuditLogService auditLogService;

    /**
     * 管理员分页查询审计日志
     */
    @RequestMapping(value = "/list", method = RequestMethod.GET)
    @RequireRole("admin")
    public Response<Map<String, Object>> list(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "resourceType", required = false) String resourceType,
            @RequestParam(value = "result", required = false) String result,
            @RequestParam(value = "startTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(value = "endTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        try {
            int normalizedPage = Math.max(page, 1);
            int normalizedPageSize = clampPageSize(pageSize);

            AuditQueryVO query = AuditQueryVO.builder()
                    .userId(userId)
                    .action(action)
                    .resourceType(resourceType)
                    .result(result)
                    .startTime(startTime)
                    .endTime(endTime)
                    .page(normalizedPage)
                    .pageSize(normalizedPageSize)
                    .build();

            List<AuditLogEntity> list = auditLogService.queryByCondition(query);
            long total = auditLogService.countByCondition(query);

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
            log.error("管理员查询审计日志列表失败", e);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /** 管理员视角 — 按 action 聚合（接收同名过滤字段，保持与 /list 一致的语义） */
    @RequestMapping(value = "/stats/by_action", method = RequestMethod.GET)
    @RequireRole("admin")
    public Response<List<AuditStatVO>> statByAction(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "resourceType", required = false) String resourceType,
            @RequestParam(value = "result", required = false) String result,
            @RequestParam(value = "startTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(value = "endTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime) {
        return doStat(auditLogService::statByAction, userId, action, resourceType, result, startTime, endTime, "by_action");
    }

    /** 管理员视角 — 按 userId 聚合（接收同名过滤字段） */
    @RequestMapping(value = "/stats/by_user", method = RequestMethod.GET)
    @RequireRole("admin")
    public Response<List<AuditStatVO>> statByUser(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "resourceType", required = false) String resourceType,
            @RequestParam(value = "result", required = false) String result,
            @RequestParam(value = "startTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(value = "endTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime) {
        return doStat(auditLogService::statByUser, userId, action, resourceType, result, startTime, endTime, "by_user");
    }

    /** 通用聚合执行封装：构造 query 时透传全部过滤字段，确保聚合与明细查询条件一致 */
    private Response<List<AuditStatVO>> doStat(
            java.util.function.Function<AuditQueryVO, List<AuditStatVO>> fn,
            Long userId,
            String action,
            String resourceType,
            String result,
            Date startTime,
            Date endTime,
            String tag) {
        try {
            AuditQueryVO query = AuditQueryVO.builder()
                    .userId(userId)
                    .action(action)
                    .resourceType(resourceType)
                    .result(result)
                    .startTime(startTime)
                    .endTime(endTime)
                    .build();
            List<AuditStatVO> rows = fn.apply(query);
            return Response.<List<AuditStatVO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(rows == null ? Collections.emptyList() : rows)
                    .build();
        } catch (Exception e) {
            log.error("管理员审计日志聚合失败 tag={}", tag, e);
            return Response.<List<AuditStatVO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private int clampPageSize(int pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
