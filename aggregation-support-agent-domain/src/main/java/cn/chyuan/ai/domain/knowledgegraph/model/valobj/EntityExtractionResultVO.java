package cn.chyuan.ai.domain.knowledgegraph.model.valobj;

import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphEntity;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphRelation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 实体抽取结果值对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EntityExtractionResultVO {

    /** 抽取的实体列表 */
    private List<GraphEntity> entities;

    /** 抽取的关系列表 */
    private List<GraphRelation> relations;

    /** LLM 原始响应 */
    private String rawLlmResponse;

    /** 对应的分块索引 */
    private int chunkIndex;
}
