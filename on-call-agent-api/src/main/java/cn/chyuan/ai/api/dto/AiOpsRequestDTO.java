package cn.chyuan.ai.api.dto;

import lombok.Data;

/**
 * AIOps 智能运维请求 DTO — 触发一键告警分析
 */
@Data
public class AiOpsRequestDTO {

    /** 智能体 ID（默认使用 AIOps 智能体 200002） */
    private String agentId;

    /** 用户 ID */
    private String userId;

    /** 会话 ID（可选，为空时自动创建） */
    private String sessionId;

    /** 告警描述（用户输入的问题或告警描述） */
    private String alertDescription;

}
