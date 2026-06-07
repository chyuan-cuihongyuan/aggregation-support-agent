package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.util.Map;

/**
 * 跨模态检索结果 DTO
 */
@Data
public class CrossModalSearchResultDTO {

    private String itemId;
    /** TEXT | IMAGE */
    private String modalityType;
    private String content;
    private String imageUrl;
    private Float score;
    private Map<String, Object> metadata;
}
