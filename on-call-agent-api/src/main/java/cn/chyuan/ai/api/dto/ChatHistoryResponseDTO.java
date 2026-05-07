package cn.chyuan.ai.api.dto;

import lombok.Data;

@Data
public class ChatHistoryResponseDTO {

    private Long id;
    private String userId;
    private String agentId;
    private String agentName;
    private String sessionId;
    private String question;
    private String answer;
    private String createTime;
}
