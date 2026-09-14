package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图谱质量指标值对象（AM8：覆盖率/连通分量/孤儿率/社区规模分布）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GraphQualityVO {

    /** 覆盖率：有来源块映射的实体占比（空图为 0） */
    private double sourceCoverage;

    /** 连通分量数 */
    private int connectedComponents;

    /** 孤儿实体率：零度数节点占比（空图为 0） */
    private double orphanRate;

    /** 最大社区占比（成员数/总节点数） */
    private double largestCommunityRatio;

    /** 社区规模分布熵（规模归一化 -Σp·ln p） */
    private double communitySizeEntropy;

    /** 评估节点总数 */
    private int nodeCount;
}
