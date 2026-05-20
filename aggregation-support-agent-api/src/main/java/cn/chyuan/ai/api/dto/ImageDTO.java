package cn.chyuan.ai.api.dto;

import lombok.Data;

/**
 * 图片 DTO
 */
@Data
public class ImageDTO {
    private String imageId;
    private String fileName;
    private Long fileSize;
    private String mimeType;
    private Integer width;
    private Integer height;
    private String description;
    private String imageUrl;
    private String createdAt;
}
