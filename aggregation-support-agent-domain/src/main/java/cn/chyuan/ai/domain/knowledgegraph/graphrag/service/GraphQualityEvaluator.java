package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunityPartitionVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GroundednessReportVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphEdgeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphQualityVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.SentenceSupportVO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱质量评估器（工单 0313 AM8）。
 * 指标：来源覆盖率/连通分量数（并查遍历）/孤儿实体率/最大社区占比/社区规模熵；
 * 摘要 groundedness：摘要句须命中成员实体或社区内关系，无支撑句标记。
 * domain 纯函数。
 */
public class GraphQualityEvaluator {

    /** 四项图指标（partition 可空：社区分布两项记 0） */
    public GraphQualityVO evaluate(GraphIndexVO index, CommunityPartitionVO partition) {
        int nodeCount = index.getNodes().size();
        if (nodeCount == 0) {
            return GraphQualityVO.builder()
                    .sourceCoverage(0).connectedComponents(0).orphanRate(0)
                    .largestCommunityRatio(0).communitySizeEntropy(0).nodeCount(0)
                    .build();
        }
        // 覆盖率：实体来源映射非空
        long covered = index.getNodes().stream()
                .filter(node -> {
                    List<String> sources = index.getEntitySources().get(node.getNodeKey());
                    return sources != null && !sources.isEmpty();
                }).count();
        // 度数与连通分量
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        index.getNodes().forEach(node -> adjacency.put(node.getNodeKey(), new HashSet<>()));
        for (GraphEdgeVO edge : index.getEdges()) {
            if (adjacency.containsKey(edge.getSourceKey()) && adjacency.containsKey(edge.getTargetKey())) {
                adjacency.get(edge.getSourceKey()).add(edge.getTargetKey());
                adjacency.get(edge.getTargetKey()).add(edge.getSourceKey());
            }
        }
        int components = 0;
        Set<String> visited = new HashSet<>();
        for (String key : adjacency.keySet()) {
            if (visited.add(key)) {
                components++;
                Deque<String> stack = new ArrayDeque<>();
                stack.push(key);
                while (!stack.isEmpty()) {
                    String current = stack.pop();
                    for (String neighbor : adjacency.get(current)) {
                        if (visited.add(neighbor)) {
                            stack.push(neighbor);
                        }
                    }
                }
            }
        }
        long orphans = adjacency.values().stream().filter(Set::isEmpty).count();

        double largestRatio = 0;
        double entropy = 0;
        if (partition != null && !partition.getCommunities().isEmpty()) {
            int largest = partition.getCommunities().values().stream()
                    .mapToInt(List::size).max().orElse(0);
            largestRatio = (double) largest / nodeCount;
            entropy = 0;
            for (List<String> members : partition.getCommunities().values()) {
                double p = (double) members.size() / nodeCount;
                if (p > 0) {
                    entropy -= p * Math.log(p);
                }
            }
        }
        return GraphQualityVO.builder()
                .sourceCoverage((double) covered / nodeCount)
                .connectedComponents(components)
                .orphanRate((double) orphans / nodeCount)
                .largestCommunityRatio(largestRatio)
                .communitySizeEntropy(entropy)
                .nodeCount(nodeCount)
                .build();
    }

    /** 摘要 groundedness：逐句（复用断言切分）命中成员实体或社区内关系 */
    public GroundednessReportVO assertGroundedness(String summaryText,
                                                   List<String> memberKeys,
                                                   List<GraphEdgeVO> internalRelations) {
        List<String> sentences = new AnswerProvenance(0.5).splitAssertions(summaryText);
        List<SentenceSupportVO> supports = new ArrayList<>();
        int supportedCount = 0;
        for (String sentence : sentences) {
            String normalized = GraphIndexBuilder.normalize(sentence);
            boolean supported = false;
            for (String member : memberKeys) {
                if (!member.isEmpty() && normalized.contains(member)) {
                    supported = true;
                    break;
                }
            }
            if (!supported) {
                for (GraphEdgeVO edge : internalRelations) {
                    if (normalized.contains(edge.getSourceKey()) && normalized.contains(edge.getTargetKey())) {
                        supported = true;
                        break;
                    }
                }
            }
            if (supported) {
                supportedCount++;
            }
            supports.add(SentenceSupportVO.builder().text(sentence).supported(supported).build());
        }
        double score = sentences.isEmpty() ? 0.0 : (double) supportedCount / sentences.size();
        return GroundednessReportVO.builder()
                .sentences(supports)
                .score(score)
                .unsupportedCount(sentences.size() - supportedCount)
                .build();
    }
}
