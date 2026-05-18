package cn.chyuan.ai.domain.aiops.service.impl;

import cn.chyuan.ai.domain.aiops.adapter.repository.IAlertRepository;
import cn.chyuan.ai.domain.aiops.model.entity.AlertEntity;
import cn.chyuan.ai.domain.aiops.service.IAlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 告警服务实现
 */
@Slf4j
@Service
public class AlertServiceImpl implements IAlertService {

    @Resource
    private IAlertRepository alertRepository;

    @Override
    public List<AlertEntity> getActiveAlerts() {
        return alertRepository.queryActiveAlerts();
    }

    @Override
    public List<AlertEntity> getAlertsBySeverity(String severity) {
        return alertRepository.queryBySeverity(severity);
    }

    @Override
    public AlertEntity getAlertById(String alertId) {
        return alertRepository.queryByAlertId(alertId);
    }

    @Override
    public AlertEntity createAlert(AlertEntity alert) {
        if (alert.getAlertId() == null || alert.getAlertId().isEmpty()) {
            alert.setAlertId(UUID.randomUUID().toString().replace("-", ""));
        }
        alert.setStatus("active");
        alert.setCreateTime(new Date());
        alert.setUpdateTime(new Date());
        alertRepository.save(alert);
        log.info("创建告警: alertId={}, name={}, severity={}", alert.getAlertId(), alert.getName(), alert.getSeverity());
        return alert;
    }

    @Override
    public void updateAlertStatus(String alertId, String status) {
        alertRepository.updateStatus(alertId, status);
        log.info("更新告警状态: alertId={}, status={}", alertId, status);
    }

    @Override
    public void acknowledgeAlert(String alertId) {
        updateAlertStatus(alertId, "acknowledged");
    }

    @Override
    public void resolveAlert(String alertId) {
        updateAlertStatus(alertId, "resolved");
    }

    @Override
    public AlertStatistics getStatistics() {
        int critical = alertRepository.countBySeverity("critical");
        int warning = alertRepository.countBySeverity("warning");
        int info = alertRepository.countBySeverity("info");
        int total = critical + warning + info;
        return new AlertStatistics(critical, warning, info, total);
    }
}
