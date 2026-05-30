package cn.chyuan.ai.infrastructure.persistent.mapper;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.infrastructure.dao.po.DocumentMetadataPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentMetadataMapper {
    void insert(DocumentMetadataPO record);

    List<DocumentMetadataPO> queryByScope(@Param("scope") TenantScopeVO scope);

    /** 仅管理员可用：不带租户隔离，普通业务请使用 queryByDocumentIdAndScope */
    DocumentMetadataPO adminQueryByDocumentId(@Param("documentId") String documentId);

    DocumentMetadataPO queryByDocumentIdAndScope(@Param("documentId") String documentId,
                                                 @Param("scope") TenantScopeVO scope);

    void updateStatus(@Param("documentId") String documentId,
                      @Param("processingStatus") String processingStatus,
                      @Param("totalChunks") Integer totalChunks,
                      @Param("totalChars") Integer totalChars,
                      @Param("sectionCount") Integer sectionCount,
                      @Param("errorMessage") String errorMessage,
                      @Param("scope") TenantScopeVO scope);

    void markDeletedByDocumentId(@Param("documentId") String documentId,
                                 @Param("scope") TenantScopeVO scope);
}
