package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 加权无向边值对象（AM2：社区发现输入边）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WeightedEdgeVO {

    /** 端点A（节点键） */
    private String sourceKey;

    /** 端点B（节点键） */
    private String targetKey;

    /** 边权重（>=0） */
    private double weight;
}
