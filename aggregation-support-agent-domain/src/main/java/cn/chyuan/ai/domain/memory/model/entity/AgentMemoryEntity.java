package cn.chyuan.ai.domain.memory.model.entity;

import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Agent 记忆实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMemoryEntity {
    
    /**
     * 主键ID
     */
    private Long id;
    
    /**
     * 记忆唯一ID (UUID)
     */
    private String memoryId;
    
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
     * 记忆内容
     */
    private String content;
    
    /**
     * 内容SHA-256哈希 (用于快速去重)
     */
    private String contentHash;
    
    /**
     * 记忆类型
     */
    private MemoryType memoryType;
    
    /**
     * 记忆作用域
     */
    private String scope;
    
    /**
     * 重要性评分 0-1
     */
    private Float importance;
    
    /**
     * 来源标记
     */
    private String source;
    
    /**
     * 扩展元数据
     */
    private Map<String, Object> metadata;
    
    /**
     * 状态: 1-有效 0-已删除
     */
    private Integer status;
    
    /**
     * 过期时间 (NULL表示永不过期)
     */
    private Instant expiresAt;
    
    /**
     * 创建时间
     */
    private Instant createdAt;
    
    /**
     * 更新时间
     */
    private Instant updatedAt;

    /**
     * 向量检索相似度分数（非持久化字段，仅在搜索结果中填充）
     * <p>
     * 由 Milvus COSINE 度量直接返回，范围 [0, 1]，值越大越相似。
     * 避免在 calculateMatch 中重复调用嵌入 API 计算余弦相似度。
     */
    private Double searchScore;
}
