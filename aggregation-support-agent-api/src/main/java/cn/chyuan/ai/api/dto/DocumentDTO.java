package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.util.Date;

@Data
public class DocumentDTO {
    private String documentId;
    private String tenantId;
    private String ownerUserId;
    private String knowledgeBaseId;
    private String knowledgeBaseName;
    private String visibility;
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
