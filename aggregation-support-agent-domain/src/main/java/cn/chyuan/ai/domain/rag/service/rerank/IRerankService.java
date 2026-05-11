package cn.chyuan.ai.domain.rag.service.rerank;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;

import java.util.List;

/**
 * Rerank重排序服务接口 — 对检索结果进行精排
 * <p>
 * 使用Cross-encoder结构，将query和chunk拼接输入，
 * 整体判断相关性，精度远高于Bi-encoder
 */
public interface IRerankService {

    /**
     * 对候选结果进行重排序
     *
     * @param query      用户查询
     * @param candidates 候选结果列表
     * @param topK       返回结果数量
     * @return 重排序后的结果列表
     */
    List<VectorSearchResultVO> rerank(String query, List<VectorSearchResultVO> candidates, int topK);

    /**
     * 判断Rerank服务是否可用
     *
     * @return true表示可用
     */
    boolean isAvailable();

}
