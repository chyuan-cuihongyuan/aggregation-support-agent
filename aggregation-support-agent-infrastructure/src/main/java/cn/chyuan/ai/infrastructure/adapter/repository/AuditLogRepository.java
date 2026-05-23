package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.audit.adapter.repository.IAuditLogRepository;
import cn.chyuan.ai.domain.audit.model.entity.AuditLogEntity;
import cn.chyuan.ai.domain.audit.model.valobj.AuditQueryVO;
import cn.chyuan.ai.domain.audit.model.valobj.AuditStatVO;
import cn.chyuan.ai.infrastructure.dao.po.AuditLogPO;
import cn.chyuan.ai.infrastructure.persistent.mapper.AuditLogMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 审计日志仓储实现 — 写入 audit_log 表，提供多条件查询与聚合
 */
@Repository("auditLogRepository")
public class AuditLogRepository implements IAuditLogRepository {

    @Resource
    private AuditLogMapper auditLogMapper;

    @Override
    public void save(AuditLogEntity entity) {
        AuditLogPO po = AuditLogPO.builder()
                .userId(entity.getUserId() != null ? entity.getUserId() : 0L)
                .username(truncate(entity.getUsername(), 64))
                .action(truncate(safe(entity.getAction()), 32))
                .resourceType(truncate(entity.getResourceType(), 32))
                .resourceId(truncate(entity.getResourceId(), 128))
                .result(truncate(entity.getResult() != null ? entity.getResult() : "SUCCESS", 16))
                .traceId(truncate(entity.getTraceId(), 64))
                .detail(truncate(entity.getDetail(), 1024))
                .ipAddress(truncate(entity.getIpAddress(), 64))
                .userAgent(truncate(entity.getUserAgent(), 256))
                .createTime(entity.getCreateTime() != null ? entity.getCreateTime() : new Date())
                .build();
        auditLogMapper.insert(po);
    }

    @Override
    public List<AuditLogEntity> queryByCondition(AuditQueryVO query) {
        List<AuditLogPO> poList = auditLogMapper.selectByCondition(query);
        return poList.stream().map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public long countByCondition(AuditQueryVO query) {
        return auditLogMapper.countByCondition(query);
    }

    @Override
    public List<AuditStatVO> statByAction(AuditQueryVO query) {
        return auditLogMapper.statByAction(query);
    }

    @Override
    public List<AuditStatVO> statByUser(AuditQueryVO query) {
        return auditLogMapper.statByUser(query);
    }

    private AuditLogEntity toEntity(AuditLogPO po) {
        return AuditLogEntity.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .username(po.getUsername())
                .action(po.getAction())
                .resourceType(po.getResourceType())
                .resourceId(po.getResourceId())
                .result(po.getResult())
                .traceId(po.getTraceId())
                .detail(po.getDetail())
                .ipAddress(po.getIpAddress())
                .userAgent(po.getUserAgent())
                .createTime(po.getCreateTime())
                .build();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 字符串截断到最大长度（按 Java UTF-16 code unit 计，即 {@link String#length()}）
     * <p>
     * surrogate pair 安全：若 max 位置正好落在 surrogate pair 的 high surrogate 上，
     * 自动回退 1 位避免产生无效 UTF-16，否则写入 MySQL utf8mb4 时可能报错或乱码。
     */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int end = max;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
