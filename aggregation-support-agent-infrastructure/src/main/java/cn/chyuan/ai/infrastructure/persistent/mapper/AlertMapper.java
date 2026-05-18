package cn.chyuan.ai.infrastructure.persistent.mapper;

import cn.chyuan.ai.infrastructure.dao.po.AlertPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 告警Mapper接口
 */
@Mapper
public interface AlertMapper {

    /**
     * 插入告警
     */
    void insert(AlertPO record);

    /**
     * 根据告警ID查询
     */
    AlertPO queryByAlertId(@Param("alertId") String alertId);

    /**
     * 查询活跃告警
     */
    List<AlertPO> queryActiveAlerts();

    /**
     * 按严重程度查询
     */
    List<AlertPO> queryBySeverity(@Param("severity") String severity);

    /**
     * 查询所有告警（分页）
     */
    List<AlertPO> queryAll(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 查询告警总数
     */
    int countAll();

    /**
     * 按严重程度统计数量
     */
    int countBySeverity(@Param("severity") String severity);

    /**
     * 更新告警状态
     */
    void updateStatus(@Param("alertId") String alertId, @Param("status") String status);

    /**
     * 删除告警
     */
    void deleteByAlertId(@Param("alertId") String alertId);
}
