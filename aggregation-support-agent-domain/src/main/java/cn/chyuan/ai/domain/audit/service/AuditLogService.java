package cn.chyuan.ai.domain.audit.service;

import cn.chyuan.ai.domain.audit.adapter.repository.IAuditLogRepository;
import cn.chyuan.ai.domain.audit.model.entity.AuditLogEntity;
import cn.chyuan.ai.domain.audit.model.valobj.AuditQueryVO;
import cn.chyuan.ai.domain.audit.model.valobj.AuditStatVO;
import cn.chyuan.ai.types.enums.AuditAction;
import cn.chyuan.ai.types.enums.AuditResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 审计日志服务实现 — 异步落库 + 管理员查询委托
 * <p>
 * 写入走 {@code auditExecutor} 线程池；任何异常都被捕获并打 warn，不影响主链路。
 */
@Slf4j
@Service
public class AuditLogService implements IAuditLogService {

    /** 详情字段最长长度，超长截断 */
    private static final int MAX_DETAIL_LENGTH = 1024;

    @Resource
    private IAuditLogRepository auditLogRepository;

    /**
     * 异步落库 — 使用 {@code auditExecutor} 线程池
     * <p>
     * 内部全部 try-catch，落库异常仅 warn，不向调用方抛出。
     */
    @Override
    @Async("auditExecutor")
    public void recordAsync(Long userId,
                            String username,
                            AuditAction action,
                            String resourceType,
                            String resourceId,
                            AuditResult result,
                            String traceId,
                            String detail,
                            String ipAddress,
                            String userAgent) {
        try {
            if (action == null || result == null) {
                log.warn("审计日志写入忽略：action 或 result 为空，userId={}, action={}, result={}",
                        userId, action, result);
                return;
            }

            AuditLogEntity entity = AuditLogEntity.builder()
                    .userId(userId == null ? 0L : userId)
                    .username(safe(username))
                    .action(action.getCode())
                    .resourceType(safe(resourceType))
                    .resourceId(safe(resourceId))
                    .result(result.getCode())
                    .traceId(safe(traceId))
                    .detail(truncate(detail))
                    .ipAddress(safe(ipAddress))
                    .userAgent(safe(userAgent))
                    .createTime(new Date())
                    .build();

            auditLogRepository.save(entity);
        } catch (Exception e) {
            // 审计写入不可影响主链路
            log.warn("审计日志写入失败：userId={}, action={}, result={}, err={}",
                    userId, action, result, e.getMessage());
        }
    }

    @Override
    public List<AuditLogEntity> queryByCondition(AuditQueryVO query) {
        return auditLogRepository.queryByCondition(query);
    }

    @Override
    public long countByCondition(AuditQueryVO query) {
        return auditLogRepository.countByCondition(query);
    }

    @Override
    public List<AuditStatVO> statByAction(AuditQueryVO query) {
        return auditLogRepository.statByAction(query);
    }

    @Override
    public List<AuditStatVO> statByUser(AuditQueryVO query) {
        return auditLogRepository.statByUser(query);
    }

    /** 空值规整为空字符串 */
    private static String safe(String v) {
        return v == null ? "" : v;
    }

    /** detail 截断到 MAX_DETAIL_LENGTH（UTF-16 surrogate pair 安全：不切断半个 emoji） */
    private static String truncate(String v) {
        if (v == null) {
            return "";
        }
        if (v.length() <= MAX_DETAIL_LENGTH) {
            return v;
        }
        int end = MAX_DETAIL_LENGTH;
        if (Character.isHighSurrogate(v.charAt(end - 1))) {
            end--;
        }
        return v.substring(0, end);
    }
}
