package cn.chyuan.ai.domain.rag.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.model.entity.DocumentMetadataEntity;

import java.util.List;

public interface IDocumentMetadataRepository {
    void save(DocumentMetadataEntity entity);

    List<DocumentMetadataEntity> queryByScope(TenantScopeVO scope);

    DocumentMetadataEntity queryByDocumentId(String documentId);

    DocumentMetadataEntity queryByDocumentId(String documentId, TenantScopeVO scope);

    void updateStatus(String documentId, String status, Integer totalChunks,
                      Integer totalChars, Integer sectionCount, String errorMessage);

    void markDeletedByDocumentId(String documentId, TenantScopeVO scope);
}
