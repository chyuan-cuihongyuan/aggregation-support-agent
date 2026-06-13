package cn.chyuan.ai.domain.knowledgegraph.adapter.repository;

import cn.chyuan.ai.domain.knowledgegraph.model.entity.ExtractionTaskEntity;

import java.util.List;

/**
 * 图谱构建任务仓储接口
 */
public interface IExtractionTaskRepository {

    void save(ExtractionTaskEntity entity);

    ExtractionTaskEntity queryByTaskId(String taskId);

    ExtractionTaskEntity queryByDocumentId(String documentId);

    /** 根据状态查询任务列表 */
    List<ExtractionTaskEntity> queryByStatus(String status);

    void updateStatus(String taskId, String status, int processedChunks,
                      int extractedEntities, int extractedRelations, String errorMessage);
}
