package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 社区摘要值对象（AM3：叶子=C0 基础摘要；聚合=C1/C2 上层摘要）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CommunitySummaryVO {

    /** 层级：0=C0 / 1=C1 / 2=C2 */
    private int level;

    /** 摘要主体ID（叶子=社区ID；聚合层=agg_l{level}_{序号}） */
    private String communityId;

    /** 摘要文本 */
    private String summaryText;

    /** 成员（叶子=节点键列表；聚合层=子社区/子组ID列表） */
    private List<String> members;

    /** 叶子成员节点计数（聚合层为子孙叶子总数） */
    private int memberCount;
}
