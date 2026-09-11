package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 引用溯源对齐请求 DTO（工单 0169：POST /api/v1/insight/citation-align，无状态即算）
 */
@Data
public class CitationAlignRequestDTO {

    /** 答案全文 */
    private String answer;

    /** 命中片段列表（顺序即来源索引） */
    private List<SourceItem> sources;

    /** 命中片段 */
    @Data
    public static class SourceItem {

        /** 片段索引（缺省时按列表顺序） */
        private Integer index;

        /** 片段内容（用于词面对齐） */
        private String content;
    }
}
