package cn.chyuan.ai.domain.research.service;

import cn.chyuan.ai.domain.research.model.valobj.ResearchReportVO;

import java.util.List;

/**
 * 报告导出器（工单 0353 AR7）：Markdown 报告+引用表+元数据 JSON 确定性装配。
 */
public class ReportExporter {

    /** Markdown 导出（标题/目录/章节/引用标记） */
    public String toMarkdown(ResearchReportVO report) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(report.getTopic()).append("\n\n");
        if (report.getExecutiveSummary() != null) {
            out.append("> ").append(report.getExecutiveSummary()).append("\n\n");
        }
        out.append("## 目录\n");
        for (int i = 0; i < report.getChapters().size(); i++) {
            out.append(i + 1).append(". ").append(report.getChapters().get(i).getTitle()).append('\n');
        }
        out.append('\n');
        for (int i = 0; i < report.getChapters().size(); i++) {
            ResearchReportVO.ChapterVO chapter = report.getChapters().get(i);
            out.append("## ").append(i + 1).append(". ").append(chapter.getTitle()).append('\n')
                    .append(chapter.getContent()).append('\n');
            if (chapter.isDegraded()) {
                out.append("（本章降级）\n");
            }
            out.append('\n');
        }
        out.append("## 引用\n");
        for (ResearchReportVO.CitationVO citation : report.getCitations()) {
            out.append("[").append(citation.getNo()).append("] ")
                    .append(citation.getTitle()).append(" — ").append(citation.getUrl())
                    .append("（可信度 ").append(citation.getScore()).append("）\n");
        }
        return out.toString();
    }

    /** 元数据 JSON（主题/来源数/引用数/对齐率/时长/成本字段透传） */
    public String metadataJson(ResearchReportVO report, double citationRate,
                               long durationMs, long tokenCost) {
        return "{\"topic\":\"" + escape(report.getTopic()) + "\"" +
                ",\"sources\":" + countSources(report) +
                ",\"citations\":" + report.getCitations().size() +
                ",\"citationRate\":" + citationRate +
                ",\"durationMs\":" + durationMs +
                ",\"tokenCost\":" + tokenCost +
                ",\"partial\":" + report.isPartial() +
                "}";
    }

    private long countSources(ResearchReportVO report) {
        return report.getCitations().stream().map(ResearchReportVO.CitationVO::getUrl).distinct().count();
    }

    private String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 重放一致性辅助：列表视图 */
    public List<String> exportAll(ResearchReportVO report, double citationRate,
                                  long durationMs, long tokenCost) {
        return List.of(toMarkdown(report),
                metadataJson(report, citationRate, durationMs, tokenCost));
    }
}
