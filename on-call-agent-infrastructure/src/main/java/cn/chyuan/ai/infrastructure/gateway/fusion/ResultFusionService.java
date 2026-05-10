package cn.chyuan.ai.infrastructure.gateway.fusion;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.fusion.IResultFusionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 检索结果融合服务实现 — 实现RRF等多种融合算法
 */
@Slf4j
@Service
public class ResultFusionService implements IResultFusionService {

    /** RRF平滑参数，经验值k=60 */
    @Value("${rag.retrieval.fusion.rrf-k:60}")
    private int rrfK;

    @Override
    public List<VectorSearchResultVO> rrfFusion(List<List<VectorSearchResultVO>> resultsLists, int topK) {
        log.info("RRF融合: 路径数={}, topK={}", resultsLists.size(), topK);

        // 构建等权重列表
        List<Double> weights = new ArrayList<>();
        for (int i = 0; i < resultsLists.size(); i++) {
            weights.add(1.0);
        }

        return weightedRRFFusion(resultsLists, weights, topK);
    }

    @Override
    public List<VectorSearchResultVO> weightedRRFFusion(
            List<List<VectorSearchResultVO>> resultsLists,
            List<Double> weights,
            int topK) {

        log.info("加权RRF融合: 路径数={}, weights={}, topK={}", resultsLists.size(), weights, topK);

        // 用于记录每个chunk的累积分数
        Map<String, Double> scoreMap = new LinkedHashMap<>();
        // 用于存储chunk对象（保留第一个出现的）
        Map<String, VectorSearchResultVO> chunkMap = new LinkedHashMap<>();

        for (int pathIndex = 0; pathIndex < resultsLists.size(); pathIndex++) {
            List<VectorSearchResultVO> results = resultsLists.get(pathIndex);
            double weight = pathIndex < weights.size() ? weights.get(pathIndex) : 1.0;

            for (int rank = 0; rank < results.size(); rank++) {
                VectorSearchResultVO chunk = results.get(rank);
                String key = generateChunkKey(chunk);

                // RRF公式：weight * 1 / (k + rank)
                double rrfScore = weight / (rrfK + rank + 1);

                scoreMap.merge(key, rrfScore, Double::sum);
                chunkMap.putIfAbsent(key, chunk);
            }
        }

        // 按累积分数降序排列，取topK
        List<VectorSearchResultVO> fusedResults = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    VectorSearchResultVO originalChunk = chunkMap.get(entry.getKey());
                    // 创建新的结果对象，使用RRF分数
                    return VectorSearchResultVO.builder()
                            .content(originalChunk.getContent())
                            .score(entry.getValue().floatValue())
                            .metadata(originalChunk.getMetadata())
                            .build();
                })
                .collect(Collectors.toList());

        log.info("RRF融合完成: resultCount={}", fusedResults.size());
        return fusedResults;
    }

    @Override
    public List<VectorSearchResultVO> normalizedFusion(
            List<List<VectorSearchResultVO>> resultsLists,
            List<Double> weights,
            int topK) {

        log.info("归一化融合: 路径数={}, weights={}, topK={}", resultsLists.size(), weights, topK);

        Map<String, Double> scoreMap = new LinkedHashMap<>();
        Map<String, VectorSearchResultVO> chunkMap = new LinkedHashMap<>();

        for (int pathIndex = 0; pathIndex < resultsLists.size(); pathIndex++) {
            List<VectorSearchResultVO> results = resultsLists.get(pathIndex);
            double weight = pathIndex < weights.size() ? weights.get(pathIndex) : 1.0;

            if (results.isEmpty()) continue;

            // 归一化分数到[0, 1]区间
            double maxScore = results.stream()
                    .mapToDouble(VectorSearchResultVO::getScore)
                    .max()
                    .orElse(1.0);
            double minScore = results.stream()
                    .mapToDouble(VectorSearchResultVO::getScore)
                    .min()
                    .orElse(0.0);
            double scoreRange = maxScore - minScore;

            for (VectorSearchResultVO chunk : results) {
                String key = generateChunkKey(chunk);

                // 归一化分数
                double normalizedScore = scoreRange > 0
                        ? (chunk.getScore() - minScore) / scoreRange
                        : 1.0;

                // 加权累积
                double weightedScore = weight * normalizedScore;
                scoreMap.merge(key, weightedScore, Double::sum);
                chunkMap.putIfAbsent(key, chunk);
            }
        }

        // 按累积分数降序排列，取topK
        List<VectorSearchResultVO> fusedResults = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    VectorSearchResultVO originalChunk = chunkMap.get(entry.getKey());
                    return VectorSearchResultVO.builder()
                            .content(originalChunk.getContent())
                            .score(entry.getValue().floatValue())
                            .metadata(originalChunk.getMetadata())
                            .build();
                })
                .collect(Collectors.toList());

        log.info("归一化融合完成: resultCount={}", fusedResults.size());
        return fusedResults;
    }

    /**
     * 生成chunk的唯一标识
     * 使用内容的hashCode作为key
     */
    private String generateChunkKey(VectorSearchResultVO chunk) {
        // 使用内容hash和元数据中的docId（如果有）
        String docId = chunk.getMetadata() != null
                ? (String) chunk.getMetadata().get("docId")
                : null;

        if (docId != null) {
            return docId;
        }

        // 使用内容的hashcode
        return String.valueOf(chunk.getContent().hashCode());
    }

}
