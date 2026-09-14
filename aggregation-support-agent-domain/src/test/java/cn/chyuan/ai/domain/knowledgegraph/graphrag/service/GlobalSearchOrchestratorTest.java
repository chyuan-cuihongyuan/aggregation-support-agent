package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.adapter.port.IGlobalInsightPort;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunitySummaryTreeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunitySummaryVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GlobalSearchResultVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Global Search 编排器单测（工单 0310 AM5）：批次划分/失败降级/空库边界。
 */
class GlobalSearchOrchestratorTest {

    private CommunitySummaryTreeVO treeOf(int communityCount) {
        List<CommunitySummaryVO> level0 = new ArrayList<>();
        for (int i = 0; i < communityCount; i++) {
            String id = "c_" + (char) ('a' + i);
            level0.add(CommunitySummaryVO.builder()
                    .level(0).communityId(id)
                    .summaryText("摘要" + id)
                    .members(List.of("k" + i)).memberCount(1)
                    .build());
        }
        return CommunitySummaryTreeVO.builder()
                .levels(List.of(level0)).levelCount(1).build();
    }

    @Test
    void 批次划分与map调用序确定() {
        List<String> mapCalls = new ArrayList<>();
        IGlobalInsightPort port = new IGlobalInsightPort() {
            @Override
            public List<String> map(String question, List<String> batchSummaries) {
                mapCalls.add(String.join(",", batchSummaries));
                return List.of("要点-" + batchSummaries.get(0));
            }

            @Override
            public String reduce(String question, List<String> insights) {
                return "答案（" + insights.size() + "要点）";
            }
        };
        GlobalSearchOrchestrator orchestrator = new GlobalSearchOrchestrator(2);
        GlobalSearchResultVO result = orchestrator.search(treeOf(5), "全局问题", port);
        assertEquals(3, result.getBatchesTotal());
        // 5 社区按批 2 切分：2/2/1，摘要按社区ID稳定序
        assertEquals(List.of("摘要c_a,摘要c_b", "摘要c_c,摘要c_d", "摘要c_e"), mapCalls);
        assertEquals(3, result.getInsights().size());
        assertEquals("答案（3要点）", result.getAnswer());
        assertTrue(!result.isPartial());
        assertEquals(0, result.getBatchesFailed());
    }

    @Test
    void 批次失败降级为部分答案() {
        IGlobalInsightPort port = new IGlobalInsightPort() {
            @Override
            public List<String> map(String question, List<String> batchSummaries) {
                if (batchSummaries.get(0).equals("摘要c_a")) {
                    throw new IllegalStateException("批次失败");
                }
                return List.of("要点-" + batchSummaries.get(0));
            }

            @Override
            public String reduce(String question, List<String> insights) {
                return "汇总";
            }
        };
        GlobalSearchResultVO result = new GlobalSearchOrchestrator(2).search(treeOf(5), "q", port);
        assertEquals(3, result.getBatchesTotal());
        assertEquals(1, result.getBatchesFailed());
        assertEquals(2, result.getInsights().size());
        assertEquals("汇总", result.getAnswer());
        assertTrue(result.isPartial(), "存在失败批次但仍有要点 → 部分答案");
    }

    @Test
    void 全部失败与reduce降级() {
        // 全部批次失败 → 无答案
        IGlobalInsightPort allFail = new IGlobalInsightPort() {
            @Override
            public List<String> map(String question, List<String> batchSummaries) {
                throw new IllegalStateException("全挂");
            }

            @Override
            public String reduce(String question, List<String> insights) {
                return "不会到达";
            }
        };
        GlobalSearchResultVO failed = new GlobalSearchOrchestrator(1).search(treeOf(2), "q", allFail);
        assertNull(failed.getAnswer());
        assertTrue(failed.getInsights().isEmpty());
        assertTrue(!failed.isPartial());
        assertEquals(2, failed.getBatchesFailed());
        // reduce 失败 → 要点拼接兜底
        IGlobalInsightPort reduceFail = new IGlobalInsightPort() {
            @Override
            public List<String> map(String question, List<String> batchSummaries) {
                return List.of("要点甲");
            }

            @Override
            public String reduce(String question, List<String> insights) {
                throw new IllegalStateException("reduce 挂");
            }
        };
        GlobalSearchResultVO fallback = new GlobalSearchOrchestrator(1).search(treeOf(1), "q", reduceFail);
        assertEquals("要点甲", fallback.getAnswer());
    }

    @Test
    void 空摘要库与非法输入() {
        GlobalSearchResultVO empty = new GlobalSearchOrchestrator(2).search(treeOf(0), "q",
                new IGlobalInsightPort() {
                    @Override
                    public List<String> map(String question, List<String> batchSummaries) {
                        return List.of();
                    }

                    @Override
                    public String reduce(String question, List<String> insights) {
                        return null;
                    }
                });
        assertNull(empty.getAnswer());
        assertEquals(0, empty.getBatchesTotal());
        assertTrue(empty.getInsights().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new GlobalSearchOrchestrator(0));
        assertThrows(IllegalArgumentException.class, () -> new GlobalSearchOrchestrator(1).search(treeOf(1), " ", null));
    }
}
