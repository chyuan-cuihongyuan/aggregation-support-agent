package cn.chyuan.ai.domain.aiops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.Map;

/**
 * 告警实体
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AlertEntity {
    /**
     * 告警ID
     */
    private Long id;

    /**
     * 告警唯一标识（外部系统）
     */
    private String alertId;

    /**
     * 告警严重程度: critical, warning, info
     */
    private String severity;

    /**
     * 告警名称
     */
    private String name;

    /**
     * 告警摘要
     */
    private String summary;

    /**
     * 告警来源主机
     */
    private String host;

    /**
     * 告警状态: active, resolved, acknowledged
     */
    private String status;

    /**
     * 告警来源（监控系统）
     */
    private String source;

    /**
     * 告警指标数据（JSON格式）
     */
    private String metricsJson;

    /**
     * 告警标签（JSON格式）
     */
    private String labelsJson;

    /**
     * 告警描述
     */
    private String description;

    /**
     * 告警触发时间
     */
    private Date alertTime;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
