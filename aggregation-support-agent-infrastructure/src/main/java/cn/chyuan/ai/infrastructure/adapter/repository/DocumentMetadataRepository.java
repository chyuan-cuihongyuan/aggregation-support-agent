package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.rag.adapter.repository.IDocumentMetadataRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentMetadataEntity;
import cn.chyuan.ai.infrastructure.dao.po.DocumentMetadataPO;
import cn.chyuan.ai.infrastructure.persistent.mapper.DocumentMetadataMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class DocumentMetadataRepository implements IDocumentMetadataRepository {

    @Resource
    private DocumentMetadataMapper documentMetadataMapper;

    @Override
    public void save(DocumentMetadataEntity entity) {
        Date now = new Date();
        DocumentMetadataPO po = DocumentMetadataPO.builder()
                .documentId(entity.getDocumentId())
                .fileName(entity.getFileName())
                .fileExtension(entity.getFileExtension())
                .fileSize(entity.getFileSize())
                .mimeType(entity.getMimeType())
                .totalChars(entity.getTotalChars() != null ? entity.getTotalChars() : 0)
                .totalChunks(entity.getTotalChunks() != null ? entity.getTotalChunks() : 0)
                .sectionCount(entity.getSectionCount() != null ? entity.getSectionCount() : 0)
                .processingStatus(entity.getProcessingStatus())
                .errorMessage(entity.getErrorMessage() != null ? entity.getErrorMessage() : "")
                .userId(entity.getUserId())
                .createTime(now)
                .updateTime(now)
                .build();
        documentMetadataMapper.insert(po);
    }

    @Override
    public List<DocumentMetadataEntity> queryByUserId(String userId) {
        List<DocumentMetadataPO> poList = documentMetadataMapper.queryByUserId(userId);
        return poList.stream().map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public DocumentMetadataEntity queryByDocumentId(String documentId) {
        DocumentMetadataPO po = documentMetadataMapper.queryByDocumentId(documentId);
        return po != null ? toEntity(po) : null;
    }

    @Override
    public void updateStatus(String documentId, String status, Integer totalChunks,
                             Integer totalChars, Integer sectionCount, String errorMessage) {
        documentMetadataMapper.updateStatus(documentId, status, totalChunks, totalChars, sectionCount, errorMessage);
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        documentMetadataMapper.deleteByDocumentId(documentId);
    }

    private DocumentMetadataEntity toEntity(DocumentMetadataPO po) {
        return DocumentMetadataEntity.builder()
                .id(po.getId())
                .documentId(po.getDocumentId())
                .fileName(po.getFileName())
                .fileExtension(po.getFileExtension())
                .fileSize(po.getFileSize())
                .mimeType(po.getMimeType())
                .totalChars(po.getTotalChars())
                .totalChunks(po.getTotalChunks())
                .sectionCount(po.getSectionCount())
                .processingStatus(po.getProcessingStatus())
                .errorMessage(po.getErrorMessage())
                .userId(po.getUserId())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
