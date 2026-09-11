package cn.chyuan.ai.domain.rag.service.rerank;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重排端口规则降级实现单测（工单 0164）
 * <p>
 * 覆盖验收：原序保真 / 原分保真 / topK 截断 / 空列表 / 恒可用
 */
class PassThroughRerankerTest {

    private final PassThroughReranker reranker = new PassThroughReranker();

    @Test
    void keepsOriginalOrderAndScores() {
        // 原序保真 + 原分保真：输入顺序与分数逐条一致（即便分数乱序）
        List<VectorSearchResultVO> candidates = List.of(
                result("一", 0.31f), result("二", 0.87f), result("三", 0.52f));

        List<VectorSearchResultVO> reranked = reranker.rerank("query", candidates, 10);

        assertThat(reranked).extracting(VectorSearchResultVO::getContent).containsExactly("一", "二", "三");
        assertThat(reranked).extracting(VectorSearchResultVO::getScore).containsExactly(0.31f, 0.87f, 0.52f);
        // 元数据引用保持
        assertThat(reranked.get(1).getMetadata()).isSameAs(candidates.get(1).getMetadata());
    }

    @Test
    void truncatesToTopK() {
        List<VectorSearchResultVO> candidates = List.of(
                result("一", 0.3f), result("二", 0.2f), result("三", 0.1f));

        List<VectorSearchResultVO> reranked = reranker.rerank("query", candidates, 2);

        assertThat(reranked).hasSize(2);
        assertThat(reranked).extracting(VectorSearchResultVO::getContent).containsExactly("一", "二");
    }

    @Test
    void emptyOrNullCandidatesYieldEmpty() {
        assertThat(reranker.rerank("query", Collections.emptyList(), 5)).isEmpty();
        assertThat(reranker.rerank("query", null, 5)).isEmpty();
    }

    @Test
    void negativeTopKYieldsEmpty() {
        List<VectorSearchResultVO> reranked = reranker.rerank("query", List.of(result("一", 1.0f)), -1);
        assertThat(reranked).isEmpty();
    }

    @Test
    void alwaysAvailable() {
        assertThat(reranker.isAvailable()).isTrue();
    }

    @Test
    void listMutabilitySafe() {
        // Arrays.asList 不支持 add —— 验证实现对不可变列表安全
        List<VectorSearchResultVO> candidates = Arrays.asList(result("一", 1.0f));
        assertThat(reranker.rerank("query", candidates, 5)).hasSize(1);
    }

    private VectorSearchResultVO result(String content, float score) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", "d1");
        return VectorSearchResultVO.builder()
                .content(content)
                .score(score)
                .metadata(metadata)
                .build();
    }
}
