package cn.chyuan.ai.domain.rag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 检索追踪聚合统计行 — 用于按 agentId / ownerUserId / day 等维度的统计接口返回。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RagTraceStatVO {

    /** 分组键（agentId / ownerUserId / yyyy-MM-dd） */
    private String groupKey;

    /** 该分组下的总条数 */
    private Long total;

    /** 该分组下的平均 TopK */
    private Double avgTopk;
}
