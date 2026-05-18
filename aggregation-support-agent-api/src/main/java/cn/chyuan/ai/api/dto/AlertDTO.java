package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 告警信息DTO
 */
@Data
public class AlertDTO {
    /**
     * 告警ID
     */
    private String id;

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
     * 告警时间
     */
    private String time;

    /**
     * 告警状态: active, resolved, acknowledged
     */
    private String status;

    /**
     * 告警来源（监控系统）
     */
    private String source;

    /**
     * 告警指标数据
     */
    private Map<String, Object> metrics;

    /**
     * 告警标签
     */
    private Map<String, String> labels;

    /**
     * 告警描述
     */
    private String description;
}
