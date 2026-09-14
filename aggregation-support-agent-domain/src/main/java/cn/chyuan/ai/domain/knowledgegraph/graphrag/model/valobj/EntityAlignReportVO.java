package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 实体对齐报告值对象（AM6：增量入图时实体合并/独立/冲突统计）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EntityAlignReportVO {

    /** 合并键（规范名） → 命中方式：EXACT（直接归一命中）/ ALIAS（别名表）/ NONE（新实体） */
    private Map<String, String> alignments;

    /** 类型冲突未合并实体键列表 */
    private java.util.List<String> typeConflicts;

    /** 合并实体数 */
    private int merged;

    /** 新建实体数 */
    private int created;
}
