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
                .username(safe(entity.getUsername()))
                .action(entity.getAction())
                .resourceType(safe(entity.getResourceType()))
                .resourceId(safe(entity.getResourceId()))
                .result(entity.getResult() != null ? entity.getResult() : "SUCCESS")
                .traceId(safe(entity.getTraceId()))
                .detail(truncate(safe(entity.getDetail()), 1024))
                .ipAddress(truncate(safe(entity.getIpAddress()), 64))
                .userAgent(truncate(safe(entity.getUserAgent()), 256))
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
