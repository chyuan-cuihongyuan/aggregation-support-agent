package cn.chyuan.ai.domain.aiops.service;

import cn.chyuan.ai.domain.aiops.model.entity.AlertEntity;

import java.util.List;

/**
 * 告警服务接口
 */
public interface IAlertService {

    /**
     * 获取活跃告警列表
     */
    List<AlertEntity> getActiveAlerts();

    /**
     * 按严重程度过滤告警
     */
    List<AlertEntity> getAlertsBySeverity(String severity);

    /**
     * 根据告警ID获取告警详情
     */
    AlertEntity getAlertById(String alertId);

    /**
     * 创建告警
     */
    AlertEntity createAlert(AlertEntity alert);

    /**
     * 更新告警状态
     */
    void updateAlertStatus(String alertId, String status);

    /**
     * 确认告警
     */
    void acknowledgeAlert(String alertId);

    /**
     * 解决告警
     */
    void resolveAlert(String alertId);

    /**
     * 获取告警统计信息
     */
    AlertStatistics getStatistics();

    /**
     * 告警统计信息
     */
    record AlertStatistics(int critical, int warning, int info, int total) {}
}
