package cn.chyuan.ai.domain.memory.longterm.abstraction;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 记忆抽象提炼结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AbstractionResult {
    
    /** 提炼出的语义记忆内容 */
    private String semanticContent;
    
    /** 来源情节记忆ID列表 */
    private List<String> sourceEpisodeIds;
    
    /** 置信度 0-1 */
    private Double confidence;
    
    /** 提炼原因 */
    private String reason;
    
    /** 是否成功 */
    private boolean success;
}
