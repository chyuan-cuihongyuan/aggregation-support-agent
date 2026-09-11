package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 引用溯源对齐响应 DTO（工单 0169）
 * <p>
 * citationScore = 全部句子对齐度均值，评分口径见 docs/02 引用溯源对齐评分口径文档。
 */
@Data
public class CitationAlignResponseDTO {

    /** 引用评分（对齐均值，0-1，供评测复用） */
    private Double citationScore;

    /** 逐句对齐明细 */
    private List<SentenceItem> sentences;

    /** 未对齐句列表（对齐度低于阈值） */
    private List<String> unalignedSentences;

    /** 单句对齐明细 */
    @Data
    public static class SentenceItem {

        /** 句子原文 */
        private String sentence;

        /** 最佳来源片段索引（无片段/零对齐时为 -1） */
        private Integer bestSourceIndex;

        /** 对齐度（Jaccard，0-1） */
        private Double alignment;
    }
}
