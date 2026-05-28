package cn.chyuan.ai.infrastructure.dao.po.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 记忆持久化对象
 */
@Data
@TableName("agent_memory")
public class AgentMemoryPO {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String memoryId;
    
    private String tenantId;
    
    private String userId;
    
    private String agentId;
    
    private String sessionId;
    
    private String content;
    
    private String contentHash;
    
    private String memoryType;
    
    private String scope;
    
    private Float importance;
    
    private String source;
    
    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private java.util.Map<String, Object> metadata;
    
    private Integer status;
    
    private LocalDateTime expiresAt;
    
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
}
