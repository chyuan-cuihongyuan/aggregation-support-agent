package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.aiops.adapter.repository.IAlertRepository;
import cn.chyuan.ai.domain.aiops.model.entity.AlertEntity;
import cn.chyuan.ai.infrastructure.dao.po.AlertPO;
import cn.chyuan.ai.infrastructure.persistent.mapper.AlertMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 告警仓储实现
 */
@Repository
public class AlertRepository implements IAlertRepository {

    @Resource
    private AlertMapper alertMapper;

    @Override
    public void save(AlertEntity entity) {
        Date now = new Date();
        AlertPO po = AlertPO.builder()
                .alertId(entity.getAlertId())
                .severity(entity.getSeverity())
                .name(entity.getName())
                .summary(entity.getSummary())
                .host(entity.getHost())
                .status(entity.getStatus())
                .source(entity.getSource())
                .metricsJson(entity.getMetricsJson())
                .labelsJson(entity.getLabelsJson())
                .description(entity.getDescription())
                .alertTime(entity.getAlertTime())
                .createTime(now)
                .updateTime(now)
                .build();
        alertMapper.insert(po);
    }

    @Override
    public AlertEntity queryByAlertId(String alertId) {
        AlertPO po = alertMapper.queryByAlertId(alertId);
        return po != null ? toEntity(po) : null;
    }

    @Override
    public List<AlertEntity> queryActiveAlerts() {
        return alertMapper.queryActiveAlerts().stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<AlertEntity> queryBySeverity(String severity) {
        return alertMapper.queryBySeverity(severity).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<AlertEntity> queryAll(int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return alertMapper.queryAll(offset, pageSize).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public int countAll() {
        return alertMapper.countAll();
    }

    @Override
    public int countBySeverity(String severity) {
        return alertMapper.countBySeverity(severity);
    }

    @Override
    public void updateStatus(String alertId, String status) {
        alertMapper.updateStatus(alertId, status);
    }

    @Override
    public void deleteByAlertId(String alertId) {
        alertMapper.deleteByAlertId(alertId);
    }

    private AlertEntity toEntity(AlertPO po) {
        return AlertEntity.builder()
                .id(po.getId())
                .alertId(po.getAlertId())
                .severity(po.getSeverity())
                .name(po.getName())
                .summary(po.getSummary())
                .host(po.getHost())
                .status(po.getStatus())
                .source(po.getSource())
                .metricsJson(po.getMetricsJson())
                .labelsJson(po.getLabelsJson())
                .description(po.getDescription())
                .alertTime(po.getAlertTime())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
