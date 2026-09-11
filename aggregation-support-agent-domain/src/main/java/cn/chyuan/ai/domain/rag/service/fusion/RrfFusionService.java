package cn.chyuan.ai.domain.rag.service.fusion;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RRF 混合检索融合纯函数（工单 0163，W1）
 * <p>
 * 向量路 + 关键词路两路 ranked list 融合为一路：不看原始分数，只看排名。
 * 公式：score(chunk) = Σ 1 / (k + rank)，rank 从 1 开始计（第一名 rank=1）。
 * k 为平滑因子，默认 60（经验值），可配置注入。
 * <p>
 * 本类为 domain 纯函数内核：零框架依赖、无状态、可充分单测
 * （k 因子 / 并列秩 / 单路为空 / 全空 / topK 截断）。
 */
public class RrfFusionService {

    /** 默认 RRF 平滑因子（经验值 k=60） */
    public static final int DEFAULT_K = 60;

    /** RRF 平滑因子 k */
    private final int k;

    public RrfFusionService() {
        this(DEFAULT_K);
    }

    public RrfFusionService(int k) {
        // 非法 k 回退默认值，保证纯函数对脏配置鲁棒
        this.k = k > 0 ? k : DEFAULT_K;
    }

    /**
     * 融合多路 ranked list
     *
     * @param rankedLists 多路检索结果（每路已按相关性排序，允许为空列表）
     * @param topK        返回结果数量上限
     * @return 融合后按 RRF 分数降序的结果列表（不超过 topK）
     */
    public List<VectorSearchResultVO> fuse(List<List<VectorSearchResultVO>> rankedLists, int topK) {
        // 全空 / 空 入参直接返回空列表
        if (rankedLists == null || rankedLists.isEmpty() || topK <= 0) {
            return new ArrayList<>();
        }

        // 累积 RRF 分数（key → 分数）；chunkMap 保留每路首次出现的原对象
        Map<String, Double> scoreMap = new LinkedHashMap<>();
        Map<String, VectorSearchResultVO> chunkMap = new LinkedHashMap<>();

        for (List<VectorSearchResultVO> rankedList : rankedLists) {
            if (rankedList == null || rankedList.isEmpty()) {
                // 单路为空：跳过该路，不影响其余路融合
                continue;
            }
            for (int position = 0; position < rankedList.size(); position++) {
                VectorSearchResultVO chunk = rankedList.get(position);
                // rank 从 1 开始计：score = Σ 1/(k + rank)
                double rank = position + 1;
                double rrfScore = 1.0d / (k + rank);
                String key = generateChunkKey(chunk);
                scoreMap.merge(key, rrfScore, Double::sum);
                chunkMap.putIfAbsent(key, chunk);
            }
        }

        // 按累积分数降序取 topK；同分按首次出现顺序保持稳定（并列秩确定性）
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    VectorSearchResultVO original = chunkMap.get(entry.getKey());
                    // 排序基于 double scoreMap 完成，floatValue 仅承载展示分数
                    return VectorSearchResultVO.builder()
                            .content(original.getContent())
                            .score(entry.getValue().floatValue())
                            .metadata(original.getMetadata())
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 生成 chunk 唯一标识 — 与既有 ResultFusionService 口径一致：
     * 优先 documentId + chunkIndex 定位；缺失时回退内容 SHA-256 摘要
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
        return sha256(chunk.getContent());
    }

    /** 计算文本 SHA-256 摘要（十六进制）；极端不可用时回退 长度_hashCode 降低碰撞 */
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
            return text.length() + "_" + text.hashCode();
        }
    }
}
