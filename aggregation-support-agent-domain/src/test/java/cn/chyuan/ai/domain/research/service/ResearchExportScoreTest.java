package cn.chyuan.ai.domain.research.service;

import cn.chyuan.ai.domain.research.model.valobj.OutlineVO;
import cn.chyuan.ai.domain.research.model.valobj.ResearchReportVO;
import cn.chyuan.ai.domain.research.model.valobj.SearchHitVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AR7/AR8 单测：报告导出（0353）/研究记分卡（0354）。
 */
class ResearchExportScoreTest {

    private ResearchReportVO report() {
        return ResearchReportVO.builder()
                .topic("RAG 调研")
                .executiveSummary("摘要句。")
                .chapters(List.of(
                        ResearchReportVO.ChapterVO.builder().title("技术")
                                .content("正文甲 [1]。").degraded(false).build(),
                        ResearchReportVO.ChapterVO.builder().title("风险")
                                .content("（降级占位）").degraded(true).build()))
                .citations(List.of(
                        ResearchReportVO.CitationVO.builder().no(1).title("来源甲")
                                .url("https://a.com").score(0.9).accessedAtMs(1L).build()))
                .partial(true)
                .build();
    }

    @Test
    void Markdown导出与元数据JSON重放一致() {
        ReportExporter exporter = new ReportExporter();
        ResearchReportVO report = report();
        String markdown = exporter.toMarkdown(report);
        // 结构段完整
        assertTrue(markdown.startsWith("# RAG 调研"));
        assertTrue(markdown.contains("> 摘要句。"));
        assertTrue(markdown.contains("## 目录"));
        assertTrue(markdown.contains("1. 技术"));
        assertTrue(markdown.contains("正文甲 [1]。"));
        assertTrue(markdown.contains("（本章降级）"));
        assertTrue(markdown.contains("[1] 来源甲 — https://a.com（可信度 0.9）"));
        // 重放一致
        assertEquals(markdown, exporter.toMarkdown(report()));
        // 元数据 JSON
        String meta = exporter.metadataJson(report, 0.5, 1200L, 800L);
        assertTrue(meta.contains("\"topic\":\"RAG 调研\""));
        assertTrue(meta.contains("\"sources\":1"));
        assertTrue(meta.contains("\"citationRate\":0.5"));
        assertTrue(meta.contains("\"partial\":true"));
        assertEquals(meta, exporter.metadataJson(report(), 0.5, 1200L, 800L));
        // exportAll 两件套
        assertEquals(2, exporter.exportAll(report(), 0.5, 1, 1).size());
    }

    @Test
    void 记分卡权重与评级边界() {
        ResearchScorecard scorecard = new ResearchScorecard(0.3, 0.3, 0.2, 0.2);
        // 全满分支无成本惩罚 → A
        ResearchScorecard.Result best = scorecard.score(
                new ResearchScorecard.Input(1, 1, 1, 1, 0));
        assertEquals(1.0, best.score(), 1e-9);
        assertEquals("A", best.grade());
        // 零分 → D
        assertEquals("D", scorecard.score(new ResearchScorecard.Input(0, 0, 0, 0, 0)).grade());
        // 成本惩罚封顶 0.05：满分分支 0.95 → A
        assertEquals("A", scorecard.score(new ResearchScorecard.Input(1, 1, 1, 1, 10000)).grade(),
                "成本惩罚 0.05 后仍 ≥0.8");
        assertEquals("C", scorecard.score(new ResearchScorecard.Input(0.5, 0.5, 0.5, 0.5, 0)).grade());
        // 部件计算：覆盖度与域名熵
        OutlineVO outline = OutlineVO.builder().topic("t")
                .sections(List.of(
                        OutlineVO.SectionVO.builder().perspective("p")
                                .questions(List.of("技术现状")).build(),
                        OutlineVO.SectionVO.builder().perspective("q")
                                .questions(List.of("无关问题")).build()))
                .build();
        List<SearchHitVO> evidence = List.of(
                SearchHitVO.builder().title("技术现状报告").url("https://a.com/1")
                        .snippet("技术现状 内容").build(),
                SearchHitVO.builder().title("另一条").url("https://b.com/2")
                        .snippet("其他 内容").build());
        assertEquals(0.5, scorecard.perspectiveCoverage(outline, evidence), 1e-9, "仅一个视角有证据");
        assertEquals(1.0, scorecard.domainEntropy(evidence), 1e-9, "两域名均分熵=1");
        assertEquals(0.0, scorecard.domainEntropy(List.of()), 1e-9);
        // 权重和校验
        assertThrows(IllegalArgumentException.class, () -> new ResearchScorecard(0.5, 0.5, 0.5, 0.5));
    }
}
