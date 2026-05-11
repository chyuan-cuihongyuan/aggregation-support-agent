package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class SearchTestResultDTO {
    private String query;
    private List<SearchResultItemDTO> vectorResults;
    private List<SearchResultItemDTO> bm25Results;
    private List<SearchResultItemDTO> hybridResults;
}
