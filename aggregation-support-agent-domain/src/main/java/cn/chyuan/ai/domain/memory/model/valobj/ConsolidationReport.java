package cn.chyuan.ai.domain.memory.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记忆整合报告
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsolidationReport {
    
    /**
     * 租户ID
     */
    private String tenantId;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 去重删除的数量
     */
    private int duplicatesRemoved;
    
    /**
     * 合并的数量
     */
    private int merged;
    
    /**
     * 清理过期记忆的数量
     */
    private int expiredCleaned;
    
    /**
     * 总处理记忆数
     */
    private int totalProcessed;
    
    public void incrementDuplicatesRemoved() {
        this.duplicatesRemoved++;
    }
    
    public void incrementMerged() {
        this.merged++;
    }
    
    public void incrementExpiredCleaned(int count) {
        this.expiredCleaned += count;
    }
}
