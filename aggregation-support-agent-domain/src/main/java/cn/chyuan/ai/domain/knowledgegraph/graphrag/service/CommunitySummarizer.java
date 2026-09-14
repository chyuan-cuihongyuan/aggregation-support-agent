package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.adapter.port.ICommunitySummaryPort;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunityPartitionVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunitySummaryTreeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunitySummaryVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphEdgeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 社区分层摘要装配器（工单 0308 AM3）。
 * C0=每个社区一条摘要（端口生成，失败走模板兜底）；当社区数超过上层扇出时
 * 按社区ID稳定序分桶聚合成 C1/C2（最多三层）。层级划分确定性，domain 纯函数编排。
 */
public class CommunitySummarizer {

    private final int topFanout;

    public CommunitySummarizer(int topFanout) {
        if (topFanout <= 0) {
            throw new IllegalArgumentException("上层扇出必须为正数");
        }
        this.topFanout = topFanout;
    }

    public CommunitySummaryTreeVO summarize(GraphIndexVO index,
                                            CommunityPartitionVO partition,
                                            ICommunitySummaryPort port) {
        List<List<CommunitySummaryVO>> levels = new ArrayList<>();

        // C0：逐社区摘要（社区ID稳定序），端口失败/返回空走模板兜底
        List<CommunitySummaryVO> level0 = new ArrayList<>();
        List<String> communityIds = new ArrayList<>(partition.getCommunities().keySet());
        Collections.sort(communityIds);
        for (String communityId : communityIds) {
            List<String> members = partition.getCommunities().get(communityId);
            int relationCount = countInternalRelations(index, communityId, partition);
            level0.add(CommunitySummaryVO.builder()
                    .level(0)
                    .communityId(communityId)
                    .summaryText(generate(port, communityId, members, relationCount))
                    .members(members)
                    .memberCount(members.size())
                    .build());
        }
        levels.add(level0);

        // C1/C2：社区数超过扇出才聚合，桶按稳定序切分
        List<CommunitySummaryVO> current = level0;
        for (int level = 1; level <= 2 && current.size() > topFanout; level++) {
            List<CommunitySummaryVO> aggregated = new ArrayList<>();
            int buckets = (current.size() + topFanout - 1) / topFanout;
            for (int bucket = 0; bucket < buckets; bucket++) {
                List<CommunitySummaryVO> children = current.subList(
                        bucket * topFanout, Math.min((bucket + 1) * topFanout, current.size()));
                List<String> childIds = new ArrayList<>();
                int leafCount = 0;
                StringBuilder merged = new StringBuilder();
                for (CommunitySummaryVO child : children) {
                    childIds.add(child.getCommunityId());
                    leafCount += child.getMemberCount();
                    if (merged.length() > 0) {
                        merged.append('\n');
                    }
                    merged.append(child.getSummaryText());
                }
                String aggregateId = "agg_l" + level + "_" + bucket;
                aggregated.add(CommunitySummaryVO.builder()
                        .level(level)
                        .communityId(aggregateId)
                        .summaryText(generate(port, aggregateId, childIds, leafCount))
                        .members(childIds)
                        .memberCount(leafCount)
                        .build());
            }
            levels.add(aggregated);
            current = aggregated;
        }
        return CommunitySummaryTreeVO.builder().levels(levels).levelCount(levels.size()).build();
    }

    /** 端口生成；null/异常回落模板（成员清单+内部关系数） */
    private String generate(ICommunitySummaryPort port, String communityId,
                            List<String> members, int relationCount) {
        if (port != null) {
            try {
                String text = port.summarize(communityId, members, relationCount);
                if (text != null && !text.trim().isEmpty()) {
                    return text;
                }
            } catch (RuntimeException ignored) {
                // 端口异常走模板兜底
            }
        }
        return "社区 " + communityId + "：成员实体 " + String.join("、", members)
                + "；内部关系 " + relationCount + " 条。";
    }

    /** 统计两端点同属该社区的边数 */
    private int countInternalRelations(GraphIndexVO index, String communityId,
                                       CommunityPartitionVO partition) {
        Map<String, String> nodeToCommunity = new HashMap<>(partition.getNodeToCommunity());
        int count = 0;
        for (GraphEdgeVO edge : index.getEdges()) {
            String sourceCommunity = nodeToCommunity.get(edge.getSourceKey());
            String targetCommunity = nodeToCommunity.get(edge.getTargetKey());
            if (communityId.equals(sourceCommunity) && communityId.equals(targetCommunity)) {
                count++;
            }
        }
        return count;
    }
}
