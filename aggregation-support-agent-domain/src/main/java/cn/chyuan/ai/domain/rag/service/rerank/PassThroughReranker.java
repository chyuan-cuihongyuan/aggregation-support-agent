package cn.chyuan.ai.domain.rag.service.rerank;

import cn.chyuan.ai.domain.rag.adapter.port.IRerankPort;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 重排端口规则降级实现（工单 0164，W2）— 原分数原序直通
 * <p>
 * W 簇端口化裁定「LLM/上游端口必带规则兜底」：rag.rerank-provider=none（默认）
 * 或 upstream 异常时的规则降级路径 —— 不改分数、不改顺序，仅按 topK 截断，
 * 数学上等价于无重排，保证关闭态零回归。
 * <p>
 * domain 纯实现：零框架依赖、无状态。
 */
public class PassThroughReranker implements IRerankPort {

    @Override
    public List<VectorSearchResultVO> rerank(String query, List<VectorSearchResultVO> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        // 原分数原序保真：仅截断 topK
        return candidates.stream()
                .limit(Math.max(topK, 0))
                .collect(Collectors.toList());
    }

    @Override
    public boolean isAvailable() {
        // 规则降级恒可用
        return true;
    }
}
