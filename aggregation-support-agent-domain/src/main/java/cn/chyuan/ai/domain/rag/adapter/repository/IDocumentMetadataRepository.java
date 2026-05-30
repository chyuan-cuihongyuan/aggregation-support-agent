package cn.chyuan.ai.domain.rag.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.model.entity.DocumentMetadataEntity;

import java.util.List;

public interface IDocumentMetadataRepository {
    void save(DocumentMetadataEntity entity);

    List<DocumentMetadataEntity> queryByScope(TenantScopeVO scope);

    /** 仅管理员可用：不带租户隔离，普通业务请使用 queryByDocumentId(String, TenantScopeVO) */
    DocumentMetadataEntity adminQueryByDocumentId(String documentId);

    DocumentMetadataEntity queryByDocumentId(String documentId, TenantScopeVO scope);

    void updateStatus(String documentId, String status, Integer totalChunks,
                      Integer totalChars, Integer sectionCount, String errorMessage,
                      TenantScopeVO scope);

    void markDeletedByDocumentId(String documentId, TenantScopeVO scope);
}
