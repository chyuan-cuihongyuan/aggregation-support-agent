package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
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
                .tenantId(entity.getTenantId())
                .ownerUserId(entity.getOwnerUserId())
                .fileName(entity.getFileName())
                .fileExtension(entity.getFileExtension())
                .fileSize(entity.getFileSize())
                .mimeType(entity.getMimeType())
                .totalChars(entity.getTotalChars() != null ? entity.getTotalChars() : 0)
                .totalChunks(entity.getTotalChunks() != null ? entity.getTotalChunks() : 0)
                .sectionCount(entity.getSectionCount() != null ? entity.getSectionCount() : 0)
                .processingStatus(entity.getProcessingStatus())
                .errorMessage(entity.getErrorMessage() != null ? entity.getErrorMessage() : "")
                .visibility(entity.getVisibility() != null ? entity.getVisibility() : "private")
                .deletedFlag(entity.getDeletedFlag() != null ? entity.getDeletedFlag() : 0)
                .userId(entity.getUserId())
                .createTime(now)
                .updateTime(now)
                .build();
        documentMetadataMapper.insert(po);
    }

    @Override
    public List<DocumentMetadataEntity> queryByScope(TenantScopeVO scope) {
        List<DocumentMetadataPO> poList = documentMetadataMapper.queryByScope(scope);
        return poList.stream().map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public DocumentMetadataEntity queryByDocumentId(String documentId) {
        DocumentMetadataPO po = documentMetadataMapper.queryByDocumentId(documentId);
        return po != null ? toEntity(po) : null;
    }

    @Override
    public DocumentMetadataEntity queryByDocumentId(String documentId, TenantScopeVO scope) {
        DocumentMetadataPO po = documentMetadataMapper.queryByDocumentIdAndScope(documentId, scope);
        return po != null ? toEntity(po) : null;
    }

    @Override
    public void updateStatus(String documentId, String status, Integer totalChunks,
                             Integer totalChars, Integer sectionCount, String errorMessage) {
        documentMetadataMapper.updateStatus(documentId, status, totalChunks, totalChars, sectionCount, errorMessage);
    }

    @Override
    public void markDeletedByDocumentId(String documentId, TenantScopeVO scope) {
        documentMetadataMapper.markDeletedByDocumentId(documentId, scope);
    }

    private DocumentMetadataEntity toEntity(DocumentMetadataPO po) {
        return DocumentMetadataEntity.builder()
                .id(po.getId())
                .documentId(po.getDocumentId())
                .tenantId(po.getTenantId())
                .ownerUserId(po.getOwnerUserId())
                .fileName(po.getFileName())
                .fileExtension(po.getFileExtension())
                .fileSize(po.getFileSize())
                .mimeType(po.getMimeType())
                .totalChars(po.getTotalChars())
                .totalChunks(po.getTotalChunks())
                .sectionCount(po.getSectionCount())
                .processingStatus(po.getProcessingStatus())
                .errorMessage(po.getErrorMessage())
                .visibility(po.getVisibility())
                .deletedFlag(po.getDeletedFlag())
                .userId(po.getUserId())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
