package cn.chyuan.ai.domain.rag.adapter.port;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;

import java.util.List;

/**
 * 重排端口（工单 0164，W2）— query + chunks → 重排后的 chunks
 * <p>
 * W 簇端口化裁定：domain 零框架依赖；LLM/上游端口必带规则兜底实现。
 * <ul>
 *   <li>upstream：infrastructure HTTP 适配上游 rerank API</li>
 *   <li>none（默认）：{@code PassThroughReranker} 规则降级（原分数原序，等价无重排）</li>
 * </ul>
 * 编排挂点：检索后、生成答案前。
 */
public interface IRerankPort {

    /**
     * 对候选结果重排
     * <p>
     * 端口契约：实现不得抛出异常打断主链路（upstream 异常/超时必须降级原序原分返回）。
     *
     * @param query      用户查询
     * @param candidates 候选结果（传入顺序即既有相关度顺序）
     * @param topK       返回结果数量上限
     * @return 重排后的结果列表
     */
    List<VectorSearchResultVO> rerank(String query, List<VectorSearchResultVO> candidates, int topK);

    /**
     * 端口是否可用（upstream = API Key 已配置；规则降级恒可用）
     */
    boolean isAvailable();
}
