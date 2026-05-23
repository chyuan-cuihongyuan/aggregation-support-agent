package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 图谱统计信息 DTO
 */
@Data
public class GraphStatisticsDTO {
    private Long entityCount;
    private Long relationCount;
    private Map<String, Number> entityTypeDistribution;
    private Map<String, Number> relationTypeDistribution;
}
