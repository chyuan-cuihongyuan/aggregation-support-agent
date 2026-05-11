package cn.chyuan.ai.infrastructure.persistent.mapper;

import cn.chyuan.ai.infrastructure.dao.po.DocumentMetadataPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentMetadataMapper {
    void insert(DocumentMetadataPO record);

    List<DocumentMetadataPO> queryByUserId(@Param("userId") String userId);

    DocumentMetadataPO queryByDocumentId(@Param("documentId") String documentId);

    void updateStatus(@Param("documentId") String documentId,
                      @Param("processingStatus") String processingStatus,
                      @Param("totalChunks") Integer totalChunks,
                      @Param("totalChars") Integer totalChars,
                      @Param("sectionCount") Integer sectionCount,
                      @Param("errorMessage") String errorMessage);

    void deleteByDocumentId(@Param("documentId") String documentId);
}
