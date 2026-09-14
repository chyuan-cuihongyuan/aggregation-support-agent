package cn.chyuan.ai.domain.research.service;

import cn.chyuan.ai.domain.research.adapter.port.IDraftPort;
import cn.chyuan.ai.domain.research.model.valobj.OutlineVO;
import cn.chyuan.ai.domain.research.model.valobj.ResearchReportVO;
import cn.chyuan.ai.domain.research.model.valobj.SearchHitVO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报告合成器（工单 0351 AR5，storm/gpt-researcher 合成思想）。
 * 大纲逐章 map 起草（章节问题+证据集 → 起草端口，异常/空走模板兜底）→
 * reduce 汇总（执行摘要端口 + 目录装配）；章节失败降级为占位章节并标记部分报告。
 * 装配确定性。domain 纯函数编排。
 */
public class ReportComposer {

    private final IDraftPort port;

    public ReportComposer(IDraftPort port) {
        this.port = port;
    }

    /** 大纲 + 证据 → 报告（引用表按证据 URL 首现序编号） */
    public ResearchReportVO compose(OutlineVO outline, List<SearchHitVO> evidence, long nowMs) {
        if (outline == null || outline.getSections().isEmpty()) {
            throw new IllegalArgumentException("大纲不能为空");
        }
        List<ResearchReportVO.ChapterVO> chapters = new ArrayList<>();
        List<ResearchReportVO.CitationVO> citations = new ArrayList<>();
        List<String> chapterSummaries = new ArrayList<>();
        boolean partial = false;
        int citationNo = 1;
        for (OutlineVO.SectionVO section : outline.getSections()) {
            List<String> points = evidenceFor(section, evidence);
            if (points.isEmpty()) {
                partial = true;
                chapters.add(ResearchReportVO.ChapterVO.builder()
                        .title(section.getPerspective())
                        .content("（本章证据不足，降级占位：待补充「" + section.getPerspective() + "」视角证据。）")
                        .degraded(true)
                        .build());
                chapterSummaries.add(section.getPerspective() + "：证据不足");
                continue;
            }
            String content = draft(section.getPerspective(), points);
            StringBuilder withAnchors = new StringBuilder(content);
            for (SearchHitVO hit : pointsSource(section, evidence)) {
                withAnchors.append(" [").append(citationNo).append("]");
                citations.add(ResearchReportVO.CitationVO.builder()
                        .no(citationNo++)
                        .title(hit.getTitle())
                        .url(hit.getUrl())
                        .score(hit.getScore())
                        .accessedAtMs(nowMs)
                        .build());
                break;
            }
            chapters.add(ResearchReportVO.ChapterVO.builder()
                    .title(section.getPerspective())
                    .content(withAnchors.toString())
                    .degraded(false)
                    .build());
            chapterSummaries.add(section.getPerspective() + "：" + firstSentence(content));
        }
        String summary = summary(outline.getTopic(), chapterSummaries);
        return ResearchReportVO.builder()
                .topic(outline.getTopic())
                .chapters(chapters)
                .executiveSummary(summary)
                .citations(citations)
                .partial(partial)
                .metadata(Map.of("chapterCount", chapters.size(), "citationCount", citations.size()))
                .build();
    }

    /** 章节证据点（证据 snippet 列表，问题相关的简单包含匹配） */
    List<String> evidenceFor(OutlineVO.SectionVO section, List<SearchHitVO> evidence) {
        List<String> points = new ArrayList<>();
        for (SearchHitVO hit : evidence) {
            for (String question : section.getQuestions()) {
                if (SearchRefineLoop.relevance(question, hit) > 0) {
                    points.add(hit.getSnippet());
                    break;
                }
            }
        }
        return points;
    }

    private List<SearchHitVO> pointsSource(OutlineVO.SectionVO section, List<SearchHitVO> evidence) {
        List<SearchHitVO> out = new ArrayList<>();
        for (SearchHitVO hit : evidence) {
            for (String question : section.getQuestions()) {
                if (SearchRefineLoop.relevance(question, hit) > 0) {
                    out.add(hit);
                    break;
                }
            }
        }
        return out;
    }

    private String draft(String title, List<String> points) {
        if (port != null) {
            try {
                String content = port.draftChapter(title, points);
                if (content != null && !content.isBlank()) {
                    return content;
                }
            } catch (RuntimeException ignored) {
                // 模板兜底
            }
        }
        return "本章要点：" + String.join("；", points) + "。";
    }

    private String summary(String topic, List<String> chapterSummaries) {
        if (port != null) {
            try {
                String text = port.executiveSummary(topic, chapterSummaries);
                if (text != null && !text.isBlank()) {
                    return text;
                }
            } catch (RuntimeException ignored) {
                // 模板兜底
            }
        }
        return "《" + topic + "》执行摘要：" + String.join("；", chapterSummaries) + "。";
    }

    private String firstSentence(String content) {
        int cut = content.indexOf('。');
        return cut > 0 ? content.substring(0, cut) + "。" : content;
    }
}
