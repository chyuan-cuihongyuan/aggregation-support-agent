package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentMetadataPO implements Serializable {
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
