package cn.chyuan.ai.api.dto;

import lombok.Data;

@Data
public class SearchTestRequestDTO {
    private String query;
    /** 检索测试默认条数：对齐 dev 多路召回默认（vector/bm25=5）；链路显式传参直达
     * MilvusVectorStoreRepository#withTopK，不经 MilvusConfigProperties 兜底（G50 判据，loop-434） */
    private Integer topK = 5;
}
