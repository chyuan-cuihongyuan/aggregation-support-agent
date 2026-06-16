package cn.chyuan.ai.infrastructure.persistent.po.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实体记忆持久化对象
 */
@Data
@TableName("agent_entity_memory")
public class AgentEntityMemoryPO {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String entityId;
    private String tenantId;
    private String userId;
    private String entityType;
    private String entityName;
    
    @TableField("attributes")
    private String attributes; // JSON string
    
    @TableField("source_memory_ids")
    private String sourceMemoryIds; // JSON string
    
    private Double confidence;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
