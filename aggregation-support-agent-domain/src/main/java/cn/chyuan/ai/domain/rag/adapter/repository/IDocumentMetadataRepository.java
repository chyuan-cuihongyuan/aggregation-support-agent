package cn.chyuan.ai.domain.rag.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.model.entity.DocumentMetadataEntity;

import java.util.List;

public interface IDocumentMetadataRepository {
    void save(DocumentMetadataEntity entity);

    List<DocumentMetadataEntity> queryByScope(TenantScopeVO scope);

    List<DocumentMetadataEntity> queryByScopeAndKnowledgeBaseId(TenantScopeVO scope, String knowledgeBaseId);

    DocumentMetadataEntity queryByDocumentId(String documentId);

    DocumentMetadataEntity queryByDocumentId(String documentId, TenantScopeVO scope);

    void updateStatus(String documentId, String status, Integer totalChunks,
                      Integer totalChars, Integer sectionCount, String errorMessage);

    void markDeletedByDocumentId(String documentId, TenantScopeVO scope);

    /**
     * 同租户+用户范围内是否存在同内容哈希的未删除文档（上传去重判重）
     */
    boolean existsByContentHash(String contentHash, TenantScopeVO scope);
}
