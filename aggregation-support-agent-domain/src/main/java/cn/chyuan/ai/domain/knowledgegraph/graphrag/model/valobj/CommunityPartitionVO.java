package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 社区划分值对象（AM2：节点→社区映射 + 社区→成员列表，确定性可重放）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CommunityPartitionVO {

    /** 节点键→社区ID（社区ID = "c_" + 成员最小节点键） */
    private Map<String, String> nodeToCommunity;

    /** 社区ID→成员节点键列表（成员有序） */
    private Map<String, List<String>> communities;

    /** 实际迭代轮数 */
    private int iterations;

    /** 是否自然收敛（false = 迭代上限出口） */
    private boolean converged;
}
