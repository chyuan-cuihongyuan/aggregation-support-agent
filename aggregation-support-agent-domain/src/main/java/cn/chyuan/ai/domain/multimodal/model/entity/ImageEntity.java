package cn.chyuan.ai.domain.multimodal.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 图片实体
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ImageEntity {

    private String imageId;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String mimeType;
    private Integer width;
    private Integer height;
    private String description;
    private float[] embedding;
    private String sourceDocumentId;
    private String userId;
    private Date createdAt;
}
