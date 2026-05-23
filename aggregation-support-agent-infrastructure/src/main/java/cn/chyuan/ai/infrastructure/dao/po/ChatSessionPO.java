package cn.chyuan.ai.infrastructure.dao.po;

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
public class ChatSessionPO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String sessionId;
    private String agentId;
    private String tenantId;
    private String ownerUserId;
    private String traceId;
    private Date createTime;
    private Date updateTime;
}
