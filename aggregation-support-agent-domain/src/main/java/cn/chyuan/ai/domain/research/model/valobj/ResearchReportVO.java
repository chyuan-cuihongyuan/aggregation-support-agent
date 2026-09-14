package cn.chyuan.ai.domain.research.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 研究报告值对象（AR5/AR7：章节草稿 + 引用表 + 元数据）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ResearchReportVO {

    /** 主题 */
    private String topic;

    /** 章节（标题+正文） */
    private List<ChapterVO> chapters;

    /** 执行摘要 */
    private String executiveSummary;

    /** 引用表（编号从 1 起） */
    private List<CitationVO> citations;

    /** 元数据 */
    private Map<String, Object> metadata;

    /** 是否部分报告（存在失败章节） */
    private boolean partial;

    /** 章节值对象 */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ChapterVO {
        private String title;
        private String content;
        private boolean degraded;
    }

    /** 引用值对象 */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CitationVO {
        private int no;
        private String title;
        private String url;
        private double score;
        private long accessedAtMs;
    }
}
