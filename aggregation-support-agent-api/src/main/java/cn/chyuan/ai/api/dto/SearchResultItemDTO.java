package cn.chyuan.ai.api.dto;

import lombok.Data;

@Data
public class SearchResultItemDTO {
    private String content;
    private Float score;
    private String source;
    private Integer chunkIndex;
}
