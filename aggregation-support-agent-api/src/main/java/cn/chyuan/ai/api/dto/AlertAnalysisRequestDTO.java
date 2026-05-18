package cn.chyuan.ai.api.dto;

import lombok.Data;

/**
 * 告警分析请求DTO
 */
@Data
public class AlertAnalysisRequestDTO {
    /**
     * 告警ID
     */
    private String alertId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 智能体ID（可选，默认使用AIOps智能体）
     */
    private String agentId;
}
