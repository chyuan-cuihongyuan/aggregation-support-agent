package cn.chyuan.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatHistoryEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String userId;
    private String agentId;
    private String agentName;
    private String sessionId;
    private String question;
    private String answer;
    private Date createTime;
    private Date updateTime;
}
