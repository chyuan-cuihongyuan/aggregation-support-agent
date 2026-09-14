package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 摘要 groundedness 评估值对象（AM8：逐句支撑标记 + 总分）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GroundednessReportVO {

    /** 逐句支撑检查（句子 + 是否有图内支撑） */
    private List<SentenceSupportVO> sentences;

    /** groundedness 得分：有支撑句占比（空摘要为 0） */
    private double score;

    /** 无支撑句数 */
    private int unsupportedCount;
}
