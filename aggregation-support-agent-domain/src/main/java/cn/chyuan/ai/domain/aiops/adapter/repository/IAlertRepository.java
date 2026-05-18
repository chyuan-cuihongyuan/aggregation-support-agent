package cn.chyuan.ai.domain.aiops.adapter.repository;

import cn.chyuan.ai.domain.aiops.model.entity.AlertEntity;

import java.util.List;

/**
 * 告警仓储接口
 */
public interface IAlertRepository {

    /**
     * 保存告警
     */
    void save(AlertEntity entity);

    /**
     * 根据告警ID查询
     */
    AlertEntity queryByAlertId(String alertId);

    /**
     * 查询活跃告警列表
     */
    List<AlertEntity> queryActiveAlerts();

    /**
     * 按严重程度查询告警
     */
    List<AlertEntity> queryBySeverity(String severity);

    /**
     * 查询所有告警（分页）
     */
    List<AlertEntity> queryAll(int page, int pageSize);

    /**
     * 查询告警总数
     */
    int countAll();

    /**
     * 查询各状态告警数量
     */
    int countBySeverity(String severity);

    /**
     * 更新告警状态
     */
    void updateStatus(String alertId, String status);

    /**
     * 删除告警
     */
    void deleteByAlertId(String alertId);
}
