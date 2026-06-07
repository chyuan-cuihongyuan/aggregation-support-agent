package cn.chyuan.ai.infrastructure.gateway.fusion;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.fusion.IResultFusionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 检索结果融合服务实现 — 实现RRF等多种融合算法
 */
@Slf4j
@Service
public class ResultFusionService implements IResultFusionService {

    /** RRF平滑参数，经验值k=60 */
    @Value("${rag.retrieval.fusion.rrf-k}")
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
                    // 排序已基于 double scoreMap 完成（见上方 sorted），此处 floatValue 仅承载展示分数，不影响排序结果
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
     * <p>
     * 优先使用元数据中的文档+分块定位信息（documentId + chunkIndex）作为稳定 key；
     * 缺失时回退到内容的 SHA-256 摘要，避免 String.hashCode() 碰撞导致不同内容被错误去重。
     */
    private String generateChunkKey(VectorSearchResultVO chunk) {
        Map<String, Object> metadata = chunk.getMetadata();
        if (metadata != null) {
            Object documentId = metadata.get("documentId");
            if (documentId != null) {
                Object chunkIndex = metadata.get("chunkIndex");
                return chunkIndex != null
                        ? documentId + "#" + chunkIndex
                        : String.valueOf(documentId);
            }
        }

        // 回退：使用内容的 SHA-256 摘要
        return sha256(chunk.getContent());
    }

    /**
     * 计算文本的 SHA-256 摘要（十六进制字符串）
     */
    private String sha256(String text) {
        if (text == null) {
            return "null";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // 极端情况下 SHA-256 不可用，回退到内容长度+hashCode 降低碰撞概率
            return text.length() + "_" + text.hashCode();
        }
    }

}
