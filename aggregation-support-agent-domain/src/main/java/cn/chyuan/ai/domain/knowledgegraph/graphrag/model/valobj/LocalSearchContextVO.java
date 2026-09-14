package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Local Search 上下文值对象（AM4：锚定实体+邻域实体/关系/原文块按预算打包）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LocalSearchContextVO {

    /** 原始查询 */
    private String query;

    /** 锚定节点键（未命中为 null） */
    private String anchorKey;

    /** 锚定状态：HIT / MISS / AMBIGUOUS */
    private String anchorStatus;

    /** 歧义候选节点键（仅 AMBIGUOUS 时非空） */
    private List<String> candidateKeys;

    /** 邻域实体键（锚点优先，按 BFS 距离+键序） */
    private List<String> entities;

    /** 邻域关系边键列表 */
    private List<String> relations;

    /** 来源文本块ID列表（按块序） */
    private List<String> textUnits;

    /** 估算预算占用（字符近似） */
    private int estimatedTokens;

    /** 是否因预算截断 */
    private boolean truncated;
}
