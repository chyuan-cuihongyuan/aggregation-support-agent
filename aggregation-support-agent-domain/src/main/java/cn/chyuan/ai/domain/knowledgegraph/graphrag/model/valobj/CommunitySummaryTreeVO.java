package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 社区摘要树值对象（AM3：levels[0]=C0 全部基础社区，向上逐层聚合）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CommunitySummaryTreeVO {

    /** 按层级组织的摘要（最多 3 层：C0/C1/C2） */
    private List<List<CommunitySummaryVO>> levels;

    /** 实际生成层级数 */
    private int levelCount;
}
