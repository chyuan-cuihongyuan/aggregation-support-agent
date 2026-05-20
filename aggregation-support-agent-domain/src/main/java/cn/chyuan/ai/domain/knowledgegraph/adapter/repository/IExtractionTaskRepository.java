package cn.chyuan.ai.domain.knowledgegraph.adapter.repository;

import cn.chyuan.ai.domain.knowledgegraph.model.entity.ExtractionTaskEntity;

/**
 * 图谱构建任务仓储接口
 */
public interface IExtractionTaskRepository {

    void save(ExtractionTaskEntity entity);

    ExtractionTaskEntity queryByTaskId(String taskId);

    ExtractionTaskEntity queryByDocumentId(String documentId);

    void updateStatus(String taskId, String status, int processedChunks,
                      int extractedEntities, int extractedRelations, String errorMessage);
}
