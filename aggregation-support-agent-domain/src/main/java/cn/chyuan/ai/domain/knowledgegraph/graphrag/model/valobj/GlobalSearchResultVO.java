package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Global Search 结果值对象（AM5：汇总答案 + 中间要点 + 批次执行统计）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GlobalSearchResultVO {

    /** 最终答案（全部批次失败时为 null） */
    private String answer;

    /** map 阶段产出的中间要点（失败批次跳过） */
    private List<String> insights;

    /** 批次总数 */
    private int batchesTotal;

    /** 失败被跳过的批次数 */
    private int batchesFailed;

    /** 是否部分答案（存在失败批次但仍可汇总） */
    private boolean partial;
}
