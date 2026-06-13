package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.knowledgegraph.adapter.repository.IExtractionTaskRepository;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.ExtractionTaskEntity;
import cn.chyuan.ai.infrastructure.dao.po.ExtractionTaskPO;
import cn.chyuan.ai.infrastructure.persistent.mapper.ExtractionTaskMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 图谱构建任务仓储实现
 */
@Repository
public class ExtractionTaskRepository implements IExtractionTaskRepository {

    @Resource
    private ExtractionTaskMapper extractionTaskMapper;

    @Override
    public void save(ExtractionTaskEntity entity) {
        Date now = new Date();
        ExtractionTaskPO po = ExtractionTaskPO.builder()
                .taskId(entity.getTaskId())
                .documentId(entity.getDocumentId())
                .status(entity.getStatus())
                .totalChunks(entity.getTotalChunks())
                .processedChunks(entity.getProcessedChunks())
                .extractedEntities(entity.getExtractedEntities())
                .extractedRelations(entity.getExtractedRelations())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt() : now)
                .updatedAt(now)
                .build();
        extractionTaskMapper.insert(po);
    }

    @Override
    public ExtractionTaskEntity queryByTaskId(String taskId) {
        ExtractionTaskPO po = extractionTaskMapper.queryByTaskId(taskId);
        return po != null ? toEntity(po) : null;
    }

    @Override
    public ExtractionTaskEntity queryByDocumentId(String documentId) {
        ExtractionTaskPO po = extractionTaskMapper.queryByDocumentId(documentId);
        return po != null ? toEntity(po) : null;
    }

    @Override
    public List<ExtractionTaskEntity> queryByStatus(String status) {
        List<ExtractionTaskPO> poList = extractionTaskMapper.queryByStatus(status);
        if (poList == null || poList.isEmpty()) {
            return Collections.emptyList();
        }
        return poList.stream().map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public void updateStatus(String taskId, String status, int processedChunks,
                              int extractedEntities, int extractedRelations, String errorMessage) {
        extractionTaskMapper.updateStatus(taskId, status, processedChunks,
                extractedEntities, extractedRelations, errorMessage, new Date());
    }

    private ExtractionTaskEntity toEntity(ExtractionTaskPO po) {
        return ExtractionTaskEntity.builder()
                .taskId(po.getTaskId())
                .documentId(po.getDocumentId())
                .status(po.getStatus())
                .totalChunks(po.getTotalChunks())
                .processedChunks(po.getProcessedChunks())
                .extractedEntities(po.getExtractedEntities())
                .extractedRelations(po.getExtractedRelations())
                .errorMessage(po.getErrorMessage())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}
