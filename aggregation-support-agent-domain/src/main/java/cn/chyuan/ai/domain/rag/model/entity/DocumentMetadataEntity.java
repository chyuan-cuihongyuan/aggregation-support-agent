package cn.chyuan.ai.domain.rag.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentMetadataEntity {
    private Long id;
    private String documentId;
    private String fileName;
    private String fileExtension;
    private Long fileSize;
    private String mimeType;
    private Integer totalChars;
    private Integer totalChunks;
    private Integer sectionCount;
    private String processingStatus;
    private String errorMessage;
    private String userId;
    private Date createTime;
    private Date updateTime;
}
