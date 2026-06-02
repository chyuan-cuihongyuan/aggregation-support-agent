package cn.chyuan.ai.domain.rag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SearchResultDetailVO {
    private String query;
    private List<SearchItem> vectorResults;
    private List<SearchItem> bm25Results;
    private List<SearchItem> graphResults;
    private List<SearchItem> hybridResults;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SearchItem {
        private String content;
        private Float score;
        private String source;
        private Integer chunkIndex;
        private String knowledgeBaseId;
        private String knowledgeBaseName;
    }
}
