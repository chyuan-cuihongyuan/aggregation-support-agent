package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.adapter.port.IGlobalInsightPort;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunitySummaryTreeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunitySummaryVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GlobalSearchResultVO;

import java.util.ArrayList;
import java.util.List;

/**
 * Global Search 编排器（工单 0310 AM5）。
 * 社区摘要按批次 map 出中间要点（批次失败降级跳过并标记部分答案）→
 * 全部要点 reduce 汇总最终答案。编排确定性，domain 纯函数。
 */
public class GlobalSearchOrchestrator {

    private final int batchSize;

    public GlobalSearchOrchestrator(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("批次大小必须为正数");
        }
        this.batchSize = batchSize;
    }

    public GlobalSearchResultVO search(CommunitySummaryTreeVO summaryTree, String question, IGlobalInsightPort port) {
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        if (port == null) {
            throw new IllegalArgumentException("端口不能为空");
        }
        List<CommunitySummaryVO> leafSummaries = summaryTree.getLevels().isEmpty()
                ? new ArrayList<>()
                : summaryTree.getLevels().get(0);
        if (leafSummaries.isEmpty()) {
            return GlobalSearchResultVO.builder()
                    .answer(null)
                    .insights(new ArrayList<>())
                    .batchesTotal(0)
                    .batchesFailed(0)
                    .partial(false)
                    .build();
        }

        List<List<String>> batches = new ArrayList<>();
        List<List<String>> batchSummaries = new ArrayList<>();
        for (int start = 0; start < leafSummaries.size(); start += batchSize) {
            List<CommunitySummaryVO> chunk = leafSummaries.subList(start, Math.min(start + batchSize, leafSummaries.size()));
            batchSummaries.add(chunk.stream().map(CommunitySummaryVO::getSummaryText).toList());
            batches.add(chunk.stream().map(CommunitySummaryVO::getCommunityId).toList());
        }

        List<String> insights = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < batches.size(); i++) {
            try {
                List<String> batchInsights = port.map(question, batchSummaries.get(i));
                if (batchInsights != null) {
                    insights.addAll(batchInsights);
                }
            } catch (RuntimeException e) {
                failed++;
            }
        }
        boolean partial = failed > 0 && !insights.isEmpty();
        String answer = null;
        if (!insights.isEmpty()) {
            try {
                answer = port.reduce(question, insights);
            } catch (RuntimeException e) {
                // reduce 失败降级为要点拼接
                answer = String.join("\n", insights);
            }
        }
        return GlobalSearchResultVO.builder()
                .answer(answer)
                .insights(insights)
                .batchesTotal(batches.size())
                .batchesFailed(failed)
                .partial(partial)
                .build();
    }
}
