package cn.chyuan.ai.domain.rag.service.chunker;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 父块装配器单测（工单 0166）
 * <p>
 * 覆盖验收：去重 / 父块截断 / 存量兼容 / 驼峰 key 兼容 / 空列表
 */
class ParentAssemblerTest {

    @Test
    void deduplicatesChildrenOfSameParentKeepingTopScore() {
        // 同一父块的三个子块命中 → 去重为一条父块记录（分数=首个即最高分子块）
        ParentAssembler assembler = new ParentAssembler(1000);
        List<VectorSearchResultVO> hits = List.of(
                child("子块A", "p1", 0.9f, "父块全文".repeat(3)),
                child("子块B", "p1", 0.8f, "父块全文".repeat(3)),
                child("子块C", "p1", 0.7f, "父块全文".repeat(3)));

        List<VectorSearchResultVO> assembled = assembler.assemble(hits);

        assertThat(assembled).hasSize(1);
        assertThat(assembled.get(0).getContent()).isEqualTo("父块全文父块全文父块全文");
        assertThat(assembled.get(0).getScore()).isEqualTo(0.9f);
        assertThat(assembled.get(0).getMetadata().get(ParentAssembler.META_PARENT_ASSEMBLED)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void truncatesParentTextToMaxLimit() {
        // 父块文本超上限：截断到 maxParentLength
        ParentAssembler assembler = new ParentAssembler(10);
        List<VectorSearchResultVO> hits = List.of(child("子块", "p1", 0.9f, "长".repeat(100)));

        List<VectorSearchResultVO> assembled = assembler.assemble(hits);

        assertThat(assembled.get(0).getContent()).hasSize(10);
    }

    @Test
    void legacyChunksWithoutParentIdPassThrough() {
        // 存量兼容：无 parent_id 的子块原样保留（不去重、不改内容、元数据无装配标记）
        VectorSearchResultVO legacy = VectorSearchResultVO.builder()
                .content("存量块").score(0.6f).metadata(new HashMap<>(Map.of("documentId", "d1"))).build();

        List<VectorSearchResultVO> assembled = new ParentAssembler(1000).assemble(List.of(legacy));

        assertThat(assembled).hasSize(1);
        assertThat(assembled.get(0)).isSameAs(legacy);
        assertThat(assembled.get(0).getMetadata()).doesNotContainKey(ParentAssembler.META_PARENT_ASSEMBLED);
    }

    @Test
    void parentIdWithoutTextPassesThrough() {
        // 有 parent_id 但父块文本缺失：无法回取，原样保留
        Map<String, Object> meta = new HashMap<>();
        meta.put(ParentAssembler.META_PARENT_ID, "p9");
        VectorSearchResultVO hit = VectorSearchResultVO.builder()
                .content("子块").score(0.5f).metadata(meta).build();

        List<VectorSearchResultVO> assembled = new ParentAssembler(1000).assemble(List.of(hit));

        assertThat(assembled).hasSize(1);
        assertThat(assembled.get(0).getContent()).isEqualTo("子块");
    }

    @Test
    void legacyCamelCaseKeysAreCompatible() {
        // 既有 ParentChildChunker 写入的驼峰 key 同样可回取（parentId/parentText）
        Map<String, Object> metaA = new HashMap<>();
        metaA.put("parentId", "px");
        metaA.put("parentText", "驼峰父块");
        Map<String, Object> metaB = new HashMap<>();
        metaB.put("parentId", "px");
        metaB.put("parentText", "驼峰父块");
        List<VectorSearchResultVO> hits = List.of(
                VectorSearchResultVO.builder().content("子A").score(0.9f).metadata(metaA).build(),
                VectorSearchResultVO.builder().content("子B").score(0.8f).metadata(metaB).build());

        List<VectorSearchResultVO> assembled = new ParentAssembler(1000).assemble(hits);

        assertThat(assembled).hasSize(1);
        assertThat(assembled.get(0).getContent()).isEqualTo("驼峰父块");
    }

    @Test
    void mixedHitsKeepOrder() {
        // 混合命中：装配记录与存量记录按原相关度顺序交错保留
        ParentAssembler assembler = new ParentAssembler(1000);
        List<VectorSearchResultVO> hits = List.of(
                child("高父子块", "p1", 0.9f, "父块一"),
                VectorSearchResultVO.builder().content("存量高块").score(0.85f)
                        .metadata(new HashMap<>(Map.of("documentId", "d1"))).build(),
                child("低父子块", "p2", 0.5f, "父块二"));

        List<VectorSearchResultVO> assembled = assembler.assemble(hits);

        assertThat(assembled).extracting(VectorSearchResultVO::getContent)
                .containsExactly("父块一", "存量高块", "父块二");
    }

    @Test
    void emptyOrNullHitsYieldEmpty() {
        assertThat(new ParentAssembler(1000).assemble(List.of())).isEmpty();
        assertThat(new ParentAssembler(1000).assemble(null)).isEmpty();
    }

    @Test
    void invalidLimitFallsBackToDefault() {
        // 非法上限回退默认 1000
        ParentAssembler assembler = new ParentAssembler(0);
        List<VectorSearchResultVO> assembled = assembler.assemble(
                List.of(child("子块", "p1", 1.0f, "字".repeat(1500))));
        assertThat(assembled.get(0).getContent()).hasSize(1000);
    }

    private VectorSearchResultVO child(String content, String parentId, float score, String parentText) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ParentAssembler.META_PARENT_ID, parentId);
        metadata.put(ParentAssembler.META_PARENT_TEXT, parentText);
        return VectorSearchResultVO.builder()
                .content(content)
                .score(score)
                .metadata(metadata)
                .build();
    }
}
