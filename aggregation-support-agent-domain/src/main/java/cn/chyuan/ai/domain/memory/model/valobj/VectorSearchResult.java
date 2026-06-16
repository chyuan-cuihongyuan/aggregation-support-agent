package cn.chyuan.ai.domain.memory.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 向量搜索结果值对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorSearchResult {
    
    /**
     * 记忆ID
     */
    private String memoryId;
    
    /**
     * 相似度分数
     */
    private Double score;
    
    /**
     * 元数据
     */
    private Map<String, Object> metadata;
}
