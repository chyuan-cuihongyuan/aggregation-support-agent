package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphNodeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.ProvenanceChainVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.TextUnitVO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 答案引用溯源单测（工单 0312 AM7）：切分/锚点命中/阈值边界/空答案。
 */
class AnswerProvenanceTest {

    private final AnswerProvenance provenance = new AnswerProvenance(0.2);

    private GraphIndexVO sampleIndex() {
        return GraphIndexVO.builder()
                .documentId("doc")
                .textUnits(Arrays.asList(
                        TextUnitVO.builder().unitId("doc#u0").documentId("doc").ordinal(0)
                                .content("Kafka 是分布式消息队列，用于日志采集与流式处理。").build(),
                        TextUnitVO.builder().unitId("doc#u1").documentId("doc").ordinal(1)
                                .content("RocketMQ 支持事务消息与延迟消息特性。").build()))
                .nodes(Arrays.asList(
                        GraphNodeVO.builder().nodeKey("kafka").entityName("Kafka").entityType("TECHNOLOGY").build(),
                        GraphNodeVO.builder().nodeKey("rocketmq").entityName("RocketMQ").entityType("TECHNOLOGY").build()))
                .edges(Collections.emptyList())
                .entitySources(Collections.emptyMap())
                .relationSources(Collections.emptyMap())
                .build();
    }

    @Test
    void 断言切分与实体锚点命中() {
        ProvenanceChainVO chain = provenance.chain("Kafka 是消息队列。RocketMQ 支持事务消息。", sampleIndex());
        assertEquals(2, chain.getAssertions().size());
        ProvenanceChainVO first = chain;
        assertEquals(1.0, first.getAnchoredRate());
        assertFalse(chain.getAssertions().get(0).isUnanchored());
        assertTrue(chain.getAssertions().get(0).getAnchors().contains("E:kafka"));
        assertFalse(chain.getAssertions().get(1).isUnanchored());
        assertTrue(chain.getAssertions().get(1).getAnchors().contains("E:rocketmq"));
    }

    @Test
    void 无锚点断言标记并拉低锚定率() {
        ProvenanceChainVO chain = provenance.chain("Kafka 很流行。这句完全无关随便说说。", sampleIndex());
        assertEquals(2, chain.getAssertions().size());
        assertFalse(chain.getAssertions().get(0).isUnanchored());
        assertTrue(chain.getAssertions().get(1).isUnanchored());
        assertTrue(chain.getAssertions().get(1).getAnchors().isEmpty());
        assertEquals(0.5, chain.getAnchoredRate());
    }

    @Test
    void 文本块重叠阈值边界() {
        // 断言与 u0 高重叠 → 命中 U 锚点；低重叠不命中
        ProvenanceChainVO chain = provenance.chain(
                "Kafka 是分布式消息队列，用于日志采集与流式处理场景。", sampleIndex());
        assertTrue(chain.getAssertions().get(0).getAnchors().stream()
                .anyMatch(anchor -> anchor.startsWith("U:")), "高重叠断言应命中文本块锚点");
        // 阈值 1.0：只有完全包含全部 bigram 才命中（不可达）→ 仅实体锚点
        AnswerProvenance strict = new AnswerProvenance(1.0);
        ProvenanceChainVO strictChain = strict.chain("Kafka 概念陈述。", sampleIndex());
        assertTrue(strictChain.getAssertions().get(0).getAnchors().stream()
                .noneMatch(anchor -> anchor.startsWith("U:")));
    }

    @Test
    void 关系锚点两端同现() {
        GraphIndexVO withEdge = GraphIndexVO.builder()
                .documentId("doc")
                .textUnits(Collections.emptyList())
                .nodes(Arrays.asList(
                        GraphNodeVO.builder().nodeKey("kafka").entityName("Kafka").build(),
                        GraphNodeVO.builder().nodeKey("rocketmq").entityName("RocketMQ").build()))
                .edges(Collections.singletonList(cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphEdgeVO.builder()
                        .edgeKey("kafka|COMPARE_TO|rocketmq")
                        .sourceKey("kafka").targetKey("rocketmq")
                        .relationType("COMPARE_TO").build()))
                .entitySources(Collections.emptyMap())
                .relationSources(Collections.emptyMap())
                .build();
        ProvenanceChainVO chain = provenance.chain("Kafka 与 RocketMQ 常被对比。", withEdge);
        assertTrue(chain.getAssertions().get(0).getAnchors().contains("R:kafka|COMPARE_TO|rocketmq"));
    }

    @Test
    void 空答案与非法阈值() {
        ProvenanceChainVO empty = provenance.chain("", sampleIndex());
        assertTrue(empty.getAssertions().isEmpty());
        assertEquals(0.0, empty.getAnchoredRate());
        ProvenanceChainVO blank = provenance.chain("   ", sampleIndex());
        assertTrue(blank.getAssertions().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new AnswerProvenance(0));
        assertThrows(IllegalArgumentException.class, () -> new AnswerProvenance(1.5));
    }
}
