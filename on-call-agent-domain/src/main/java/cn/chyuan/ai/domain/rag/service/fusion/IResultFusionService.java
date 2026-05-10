package cn.chyuan.ai.domain.rag.service.fusion;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;

import java.util.List;

/**
 * 检索结果融合服务接口 — 将多路检索结果融合为统一排序
 */
public interface IResultFusionService {

    /**
     * RRF融合（Reciprocal Rank Fusion）— 基于排名的融合算法
     * <p>
     * 核心思想：不看原始分数，只看排名
     * 公式：score(chunk) = Σ(1 / (k + rank))
     * k = 60（平滑参数，经验值）
     *
     * @param resultsLists 多路检索结果列表
     * @param topK         返回结果数量
     * @return 融合后的结果列表
     */
    List<VectorSearchResultVO> rrfFusion(List<List<VectorSearchResultVO>> resultsLists, int topK);

    /**
     * 加权RRF融合 — 为不同检索路径设置权重
     *
     * @param resultsLists 多路检索结果列表
     * @param weights      各路权重
     * @param topK         返回结果数量
     * @return 融合后的结果列表
     */
    List<VectorSearchResultVO> weightedRRFFusion(
            List<List<VectorSearchResultVO>> resultsLists,
            List<Double> weights,
            int topK);

    /**
     * 归一化融合 — 将各路分数归一化后加权求和
     *
     * @param resultsLists 多路检索结果列表
     * @param weights      各路权重
     * @param topK         返回结果数量
     * @return 融合后的结果列表
     */
    List<VectorSearchResultVO> normalizedFusion(
            List<List<VectorSearchResultVO>> resultsLists,
            List<Double> weights,
            int topK);

}
