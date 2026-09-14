package cn.chyuan.ai.domain.research.service;

import cn.chyuan.ai.domain.research.adapter.port.IDraftPort;
import cn.chyuan.ai.domain.research.adapter.port.IQuestionPort;
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
 * AR1/AR2/AR3 单测：大纲生成（0347）/搜索精炼循环（0348）/来源评分（0349）。
 */
class ResearchPlanSearchTest {

    @Test
    void 视角问题树装配与模板兜底() {
        OutlineGenerator generator = new OutlineGenerator(List.of("技术", "商业"), null);
        OutlineVO outline = generator.generate("RAG 平台");
        assertEquals(2, outline.getSections().size());
        assertEquals("技术", outline.getSections().get(0).getPerspective());
        assertEquals(2, outline.getSections().get(0).getQuestions().size());
        assertTrue(outline.getSections().get(0).getQuestions().get(0).contains("RAG 平台"));
        // 端口正常采纳 / 异常兜底
        OutlineGenerator withPort = new OutlineGenerator(List.of("风险"), (topic, perspective) -> {
            if (perspective.equals("风险")) {
                return List.of("数据泄漏风险？");
            }
            throw new IllegalStateException("挂");
        });
        assertEquals(List.of("数据泄漏风险？"), withPort.generate("x").getSections().get(0).getQuestions());
        assertThrows(IllegalArgumentException.class, () -> new OutlineGenerator(List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(" "));
    }

    @Test
    void 搜索评分缺口识别与补搜() {
        // 本地语料兜底式搜索端口：查询含关键词才命中
        SourceScorer scorer = new SourceScorer(Map.of(), 1000L, 100000L);
        SearchRefineLoop loop = new SearchRefineLoop(0.05, 3);
        OutlineVO outline = OutlineVO.builder().topic("t")
                .sections(List.of(OutlineVO.SectionVO.builder().perspective("p")
                        .questions(List.of("技术现状", "未知领域问题")).build()))
                .build();
        SearchRefineLoop.Result result = loop.run(outline, query -> {
            if (query.contains("技术现状")) {
                return List.of(SearchHitVO.builder().title("技术现状报告").url("https://a.com/x")
                        .snippet("技术现状 相关内容摘要").authority("official").build());
            }
            return List.of();
        }, scorer);
        // 问题 1 命中，问题 2 无结果为缺口 → 预算耗尽出口
        assertFalse(result.gapsClosed());
        assertEquals(1, result.remainingGaps().size());
        assertEquals(1, result.evidence().size());
        assertEquals(3, result.rounds());
    }

    @Test
    void 来源评分权威去重与新鲜度() {
        SourceScorer scorer = new SourceScorer(Map.of("a.com", 1.0), 1000L, 100L);
        List<SearchHitVO> scored = scorer.score(List.of(
                SearchHitVO.builder().title("A").url("https://a.com/1").snippet("内容甲")
                        .authority("unknown").build(),
                SearchHitVO.builder().title("B").url("https://c.com/1").snippet("内容乙")
                        .authority("ugc").build(),
                SearchHitVO.builder().title("A2").url("https://d.com/1").snippet("内容甲")
                        .authority("media").build()), Map.of("https://c.com/1", 900L));
        // 内容甲两条指纹一致 → 保留权威更高的 a.com；c.com 新鲜度衰减（50 半衰期→0.5×0.4=0.2）
        assertEquals(2, scored.size());
        assertEquals("https://a.com/1", scored.get(0).getUrl());
        assertEquals(1.0, scored.get(0).getScore(), 1e-9);
        assertEquals(0.2, scored.get(1).getScore(), 1e-9);
        // 无发表时间按 1.0
        assertEquals(1.0, scorer.freshness(null), 1e-9);
        assertTrue(SourceScorer.domainOf("https://a.com/x/y").equals("a.com"));
    }

    @Test
    void 非法配置拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new SearchRefineLoop(2, 3));
        assertThrows(IllegalArgumentException.class, () -> new SearchRefineLoop(0.5, 0));
        SearchRefineLoop loop = new SearchRefineLoop(0.5, 1);
        assertThrows(IllegalArgumentException.class, () -> loop.run(
                OutlineVO.builder().sections(List.of()).build(),
                q -> List.of(), new SourceScorer(Map.of(), 0, 1)));
    }
}
