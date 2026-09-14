package cn.chyuan.ai.domain.research.service;

import cn.chyuan.ai.domain.research.adapter.port.IDraftPort;
import cn.chyuan.ai.domain.research.model.valobj.OutlineVO;
import cn.chyuan.ai.domain.research.model.valobj.ResearchReportVO;
import cn.chyuan.ai.domain.research.model.valobj.SearchHitVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AR4/AR5/AR6 单测：引用对齐（0350）/报告合成（0351）/状态机（0352）。
 */
class ResearchComposeStateTest {

    private SearchHitVO source(String url, String title, String snippet) {
        return SearchHitVO.builder().title(title).url(url).snippet(snippet)
                .score(0.9).authority("official").build();
    }

    @Test
    void 引用对齐三类结果与对齐率() {
        CitationAligner aligner = new CitationAligner(0.2);
        List<ResearchReportVO.CitationVO> citations = List.of(
                ResearchReportVO.CitationVO.builder().no(1).url("https://a.com").build(),
                ResearchReportVO.CitationVO.builder().no(2).url("https://ghost.com").build());
        List<SearchHitVO> sources = List.of(
                source("https://a.com", "RAG 技术报告", "检索增强生成 技术 架构"));
        List<CitationAligner.Alignment> alignments = aligner.align(
                "检索增强生成 是关键技术[1]。无锚点断言一句。坏锚点引用[9]。无关内容完全不同[1]。",
                citations, sources);
        assertEquals(4, alignments.size());
        assertEquals(CitationAligner.Alignment.OK, alignments.get(0).issue());
        assertEquals(CitationAligner.Alignment.NO_ANCHOR, alignments.get(1).issue());
        assertEquals(CitationAligner.Alignment.INVALID_ANCHOR, alignments.get(2).issue());
        assertEquals(CitationAligner.Alignment.WEAK_SUPPORT, alignments.get(3).issue());
        assertEquals(0.25, aligner.alignmentRate(alignments), 1e-9);
        assertEquals(0, aligner.alignmentRate(aligner.align(null, citations, sources)), 1e-9);
    }

    @Test
    void 逐章起草与降级占位与汇总成文() {
        OutlineVO outline = OutlineVO.builder().topic("RAG 平台调研")
                .sections(List.of(
                        OutlineVO.SectionVO.builder().perspective("技术")
                                .questions(List.of("技术现状")).build(),
                        OutlineVO.SectionVO.builder().perspective("法律")
                                .questions(List.of("无关问题")).build()))
                .build();
        List<SearchHitVO> evidence = List.of(
                source("https://a.com", "技术报告", "技术现状 摘要内容"));
        // 无端口走模板兜底；法律视角无证据 → 降级占位 + partial
        ResearchReportVO report = new ReportComposer(null).compose(outline, evidence, 1000L);
        assertEquals(2, report.getChapters().size());
        assertFalse(report.getChapters().get(0).isDegraded());
        assertTrue(report.getChapters().get(0).getContent().contains("技术现状 摘要内容"));
        assertTrue(report.getChapters().get(1).isDegraded());
        assertTrue(report.isPartial());
        assertEquals(1, report.getCitations().size());
        assertEquals(1, report.getCitations().get(0).getNo());
        assertTrue(report.getExecutiveSummary().contains("执行摘要"));
        // 端口正常输出优先 + 异常兜底
        IDraftPort port = new IDraftPort() {
            @Override
            public String draftChapter(String title, List<String> points) {
                if (title.equals("技术")) {
                    return "端口起草的正文。";
                }
                throw new IllegalStateException("挂");
            }

            @Override
            public String executiveSummary(String topic, List<String> chapterSummaries) {
                return "端口摘要。";
            }
        };
        ResearchReportVO ported = new ReportComposer(port).compose(outline, evidence, 1000L);
        assertEquals("端口起草的正文。 [1]", ported.getChapters().get(0).getContent());
        assertEquals("端口摘要。", ported.getExecutiveSummary());
        assertThrows(IllegalArgumentException.class, () -> new ReportComposer(null).compose(
                OutlineVO.builder().sections(List.of()).build(), evidence, 0));
    }

    @Test
    void 状态机转移矩阵与检查点续跑() {
        ResearchTaskStateMachine machine = new ResearchTaskStateMachine();
        assertEquals(ResearchTaskStateMachine.CREATED, machine.getStatus());
        machine.transition(ResearchTaskStateMachine.PLANNING);
        machine.transition(ResearchTaskStateMachine.SEARCHING);
        machine.checkpoint(ResearchTaskStateMachine.SEARCHING, Map.of("evidence", "快照"));
        machine.transition(ResearchTaskStateMachine.DRAFTING);
        // 断点续跑：最近检查点 SEARCHING → 恢复进入 DRAFTING
        assertEquals(ResearchTaskStateMachine.DRAFTING, machine.resumeFrom());
        assertEquals("快照", machine.<Map>checkpointOf(ResearchTaskStateMachine.SEARCHING).get("evidence"));
        // 非法转移（DRAFTING → PLANNING）拒绝
        assertThrows(IllegalStateException.class, () ->
                machine.transition(ResearchTaskStateMachine.PLANNING));
        // 运行态可取消
        machine.transition(ResearchTaskStateMachine.CANCELLED);
        assertEquals(ResearchTaskStateMachine.CANCELLED, machine.getStatus());
        // 终态无出边
        assertThrows(IllegalStateException.class, () ->
                machine.transition(ResearchTaskStateMachine.DONE));
        assertEquals(4, machine.trail().size(), "轨迹记录转移来源（PLANNING/SEARCHING/DRAFTING/CANCELLED 前）");
        // 无检查点从 PLANNING 恢复
        assertEquals(ResearchTaskStateMachine.PLANNING, new ResearchTaskStateMachine().resumeFrom());
    }
}
