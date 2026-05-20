package cn.chyuan.ai.domain.knowledgegraph.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 图谱构建任务实体
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExtractionTaskEntity {

    /** 任务ID */
    private String taskId;

    /** 文档ID */
    private String documentId;

    /** 任务状态：PENDING | PROCESSING | COMPLETED | FAILED */
    private String status;

    /** 总分块数 */
    private Integer totalChunks;

    /** 已处理分块数 */
    private Integer processedChunks;

    /** 抽取实体数 */
    private Integer extractedEntities;

    /** 抽取关系数 */
    private Integer extractedRelations;

    /** 错误信息 */
    private String errorMessage;

    /** 创建时间 */
    private Date createdAt;

    /** 更新时间 */
    private Date updatedAt;
}
