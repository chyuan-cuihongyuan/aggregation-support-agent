package cn.chyuan.ai.domain.rag.service.fusion;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * RRF 混合检索融合纯函数单测（工单 0163）
 * <p>
 * 覆盖验收：k 因子 / 并列秩 / 单路为空 / 全空 / topK 截断 / 去重 key
 */
class RrfFusionServiceTest {

    @Test
    void kFactorControlsScoreMagnitude() {
        // 两路各自第一名、无重叠：score = 1/(k+1)
        List<List<VectorSearchResultVO>> lists = List.of(
                List.of(result("a", "d1", 0)),
                List.of(result("b", "d2", 0)));

        double scoreK60 = new RrfFusionService(60).fuse(lists, 10).get(0).getScore();
        double scoreK1 = new RrfFusionService(1).fuse(lists, 10).get(0).getScore();

        assertThat(scoreK60).isCloseTo(1.0d / 61, within(0.0001d));
        assertThat(scoreK1).isCloseTo(1.0d / 2, within(0.0001d));
        // k 越大分数越平滑（越小）
        assertThat(scoreK60).isLessThan(scoreK1);
    }

    @Test
    void overlappingChunkAccumulatesScoreAcrossLists() {
        // 同一 chunk（documentId#chunkIndex 定位）在两路分别排第 1、第 2：
        // score = 1/(60+1) + 1/(60+2)
        List<List<VectorSearchResultVO>> lists = List.of(
                List.of(result("共享块", "d1", 0), result("仅向量", "d1", 1)),
                List.of(result("仅关键词", "d1", 2), result("共享块", "d1", 0)));

        List<VectorSearchResultVO> fused = new RrfFusionService(60).fuse(lists, 10);

        // 共享块两路累积居首；其余两块按各自 RRF 分排序（第二路头名 1/61 > 第一路次名 1/62）
        assertThat(fused).extracting(VectorSearchResultVO::getContent).containsExactly("共享块", "仅关键词", "仅向量");
        assertThat(fused.get(0).getScore()).isCloseTo((float) (1.0d / 61 + 1.0d / 62), within(0.0001f));
    }

    @Test
    void tiesKeepDeterministicOrderByFirstAppearance() {
        // 并列秩：两路各自的条目融合分相同（都只在自己那路排第 1），
        // 输出按首次出现顺序保持稳定，不会因排序抖动
        List<List<VectorSearchResultVO>> lists = List.of(
                List.of(result("第一路头名", "d1", 0), result("第一路次名", "d1", 1)),
                List.of(result("第二路头名", "d2", 0)));

        List<VectorSearchResultVO> fused = new RrfFusionService(60).fuse(lists, 10);

        assertThat(fused).extracting(VectorSearchResultVO::getContent)
                .containsExactly("第一路头名", "第二路头名", "第一路次名");
        // 首名与并列者分数完全一致
        assertThat(fused.get(0).getScore()).isEqualTo(fused.get(1).getScore());
    }

    @Test
    void singleEmptyPathYieldsOtherPathAsIs() {
        // 单路为空：另一路原样（顺序保留，分数来自该路排名）；Arrays.asList 允许 null 路参与
        List<VectorSearchResultVO> vectorPath = List.of(
                result("向量一", "d1", 0), result("向量二", "d1", 1));

        List<VectorSearchResultVO> fused = new RrfFusionService(60)
                .fuse(java.util.Arrays.asList(vectorPath, Collections.emptyList(), null), 10);

        assertThat(fused).extracting(VectorSearchResultVO::getContent).containsExactly("向量一", "向量二");
    }

    @Test
    void allEmptyPathsYieldEmptyResult() {
        assertThat(new RrfFusionService(60).fuse(List.of(), 10)).isEmpty();
        assertThat(new RrfFusionService(60).fuse(List.of(Collections.emptyList(), new ArrayList<>()), 10)).isEmpty();
        assertThat(new RrfFusionService(60).fuse(null, 10)).isEmpty();
    }

    @Test
    void topKTruncatesFusedResult() {
        List<List<VectorSearchResultVO>> lists = List.of(
                List.of(result("a", "d1", 0), result("b", "d1", 1), result("c", "d1", 2)),
                List.of(result("d", "d2", 0)));

        List<VectorSearchResultVO> fused = new RrfFusionService(60).fuse(lists, 2);

        assertThat(fused).hasSize(2);
    }

    @Test
    void invalidKFallsBackToDefault() {
        // 非法 k（<=0）回退默认 60，纯函数对脏配置鲁棒
        List<List<VectorSearchResultVO>> lists = List.of(List.of(result("a", "d1", 0)));

        float score = new RrfFusionService(0).fuse(lists, 10).get(0).getScore();

        assertThat(score).isCloseTo((float) (1.0d / 61), within(0.0001f));
    }

    @Test
    void fallbackContentShaKeyDedupesWhenMetadataMissing() {
        // 无 documentId 元数据时回退内容摘要：同内容不同路去重合并
        VectorSearchResultVO noMeta1 = VectorSearchResultVO.builder()
                .content("纯内容块").score(1.0f).metadata(new HashMap<>()).build();
        VectorSearchResultVO noMeta2 = VectorSearchResultVO.builder()
                .content("纯内容块").score(0.5f).metadata(null).build();

        List<VectorSearchResultVO> fused = new RrfFusionService(60)
                .fuse(List.of(List.of(noMeta1), List.of(noMeta2)), 10);

        assertThat(fused).hasSize(1);
        // 两路各自头名：score = 1/61 + 1/61
        assertThat(fused.get(0).getScore()).isCloseTo((float) (2.0d / 61), within(0.0001f));
    }

    private VectorSearchResultVO result(String content, String documentId, int chunkIndex) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", documentId);
        metadata.put("chunkIndex", chunkIndex);
        return VectorSearchResultVO.builder()
                .content(content)
                .score(1.0f)
                .metadata(metadata)
                .build();
    }
}
