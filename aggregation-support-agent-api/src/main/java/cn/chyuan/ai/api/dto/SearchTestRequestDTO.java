package cn.chyuan.ai.api.dto;

import lombok.Data;

@Data
public class SearchTestRequestDTO {
    private String query;
    private Integer topK = 5;
}
