package cn.chyuan.ai.domain.memory.model.valobj;

import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.Map;

/**
 * 记忆存储选项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryOptions {
    
    /**
     * 租户ID
     */
    private String tenantId;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 智能体ID
     */
    private String agentId;
    
    /**
     * 会话ID
     */
    private String sessionId;
    
    /**
     * 记忆类型
     */
    private MemoryType memoryType;
    
    /**
     * 记忆作用域
     */
    private String scope;
    
    /**
     * 来源标记
     */
    private String source;
    
    /**
     * 扩展元数据
     */
    private Map<String, Object> metadata;
    
    /**
     * 过期时间
     */
    private Duration ttl;
}
