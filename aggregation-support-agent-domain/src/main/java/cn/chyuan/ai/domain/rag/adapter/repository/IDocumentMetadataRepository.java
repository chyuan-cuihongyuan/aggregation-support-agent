package cn.chyuan.ai.domain.rag.adapter.repository;

import cn.chyuan.ai.domain.rag.model.entity.DocumentMetadataEntity;

import java.util.List;

public interface IDocumentMetadataRepository {
    void save(DocumentMetadataEntity entity);

    List<DocumentMetadataEntity> queryByUserId(String userId);

    DocumentMetadataEntity queryByDocumentId(String documentId);

    void updateStatus(String documentId, String status, Integer totalChunks,
                      Integer totalChars, Integer sectionCount, String errorMessage);

    void deleteByDocumentId(String documentId);
}
