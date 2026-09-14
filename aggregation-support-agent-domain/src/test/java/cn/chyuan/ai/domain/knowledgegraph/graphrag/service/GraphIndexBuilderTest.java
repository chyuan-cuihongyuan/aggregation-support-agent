package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.TextUnitVO;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphEntity;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphRelation;
import cn.chyuan.ai.domain.knowledgegraph.model.valobj.EntityExtractionResultVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图谱索引构建器单测（工单 0306 AM1）：切块确定性/来源块映射/重放哈希/边界。
 */
class GraphIndexBuilderTest {

    private final GraphIndexBuilder builder = new GraphIndexBuilder(50, 10);

    @Test
    void 切块确定性与重叠边界() {
        String doc = "段落一内容。\n\n段落二内容更长一些会触发滑窗切分因为超过五十个字符的阈值限制所以必须继续切分直到结尾并且这段足够长以触发滑窗。\n\n段落三。";
        GraphIndexVO index = builder.build("doc1", doc, null);
        List<TextUnitVO> units = index.getTextUnits();
        // 三段 → 至少三块，块序连续
        assertTrue(units.size() >= 3);
        for (int i = 0; i < units.size(); i++) {
            assertEquals("doc1#u" + i, units.get(i).getUnitId());
            assertEquals(i, units.get(i).getOrdinal());
        }
        // 超长段滑窗：相邻块存在重叠（下一块开头 = 上一块尾部片段）
        TextUnitVO longFirst = units.stream().filter(u -> u.getContent().startsWith("段落二")).findFirst().orElseThrow();
        TextUnitVO longSecond = units.get(units.indexOf(longFirst) + 1);
        assertTrue(longSecond.getContent().length() <= 50);
        assertTrue(longFirst.getContent().regionMatches(
                longFirst.getContent().length() - 10, longSecond.getContent(), 0, 10),
                "滑窗切块应保留 overlap 重叠");
        // 块大小上限
        units.forEach(u -> assertTrue(u.getContent().length() <= 50));
    }

    @Test
    void 实体与关系来源块映射() {
        EntityExtractionResultVO extraction = EntityExtractionResultVO.builder()
                .entities(List.of(
                        GraphEntity.builder().entityId("e1").entityName("Spring AI").entityType("TECHNOLOGY").build(),
                        GraphEntity.builder().entityId("e2").entityName("Spring Boot").entityType("TECHNOLOGY").build()))
                .relations(List.of(
                        GraphRelation.builder()
                                .sourceEntityName("Spring Boot").targetEntityName("Spring AI")
                                .relationType("DEPENDS_ON").build()))
                .build();
        String doc = "第一段只谈 Spring Boot 框架本身。\n\n第二段单独介绍 Spring AI 的集成能力。";
        GraphIndexVO index = builder.build("doc2", doc, extraction);

        assertEquals(2, index.getNodes().size());
        assertEquals(1, index.getEdges().size());
        // 实体各自命中所在块
        assertEquals(List.of("doc2#u0"), index.getEntitySources().get("springboot"));
        assertEquals(List.of("doc2#u1"), index.getEntitySources().get("springai"));
        // 关系：两端无共现块 → 退化为任一端并集
        String edgeKey = "springboot|DEPENDS_ON|springai";
        assertEquals(List.of("doc2#u0", "doc2#u1"), index.getRelationSources().get(edgeKey));
    }

    @Test
    void 关系共现块优先() {
        EntityExtractionResultVO extraction = EntityExtractionResultVO.builder()
                .entities(List.of(
                        GraphEntity.builder().entityName("Kafka").entityType("TECHNOLOGY").build(),
                        GraphEntity.builder().entityName("RocketMQ").entityType("TECHNOLOGY").build()))
                .relations(List.of(GraphRelation.builder()
                        .sourceEntityName("Kafka").targetEntityName("RocketMQ")
                        .relationType("RELATED_TO").build()))
                .build();
        String doc = "第一段单独提 Kafka。\n\n第二段单独提 RocketMQ。\n\n第三段 Kafka 与 RocketMQ 同段共现。";
        GraphIndexVO index = builder.build("doc3", doc, extraction);
        assertEquals(List.of("doc3#u2"), index.getRelationSources().get("kafka|RELATED_TO|rocketmq"));
    }

    @Test
    void 同输入重放哈希一致() {
        EntityExtractionResultVO extraction = EntityExtractionResultVO.builder()
                .entities(List.of(GraphEntity.builder().entityName("Agent").entityType("CONCEPT").build()))
                .build();
        String doc = "Agent 是自主智能体。Agent 具备规划能力。";
        GraphIndexVO first = builder.build("doc4", doc, extraction);
        GraphIndexVO second = builder.build("doc4", doc, extraction);
        assertEquals(first.getIndexHash(), second.getIndexHash());
        // 实体名归一进节点键（大小写/空白折叠），节点数不受影响
        assertEquals(1, second.getNodes().size());
        assertEquals("agent", second.getNodes().get(0).getNodeKey());
    }

    @Test
    void 空文档与非法配置边界() {
        GraphIndexVO empty = builder.build("doc5", "", null);
        assertTrue(empty.getTextUnits().isEmpty());
        assertTrue(empty.getNodes().isEmpty());
        assertTrue(!empty.getIndexHash().isEmpty(), "空文档也应产出确定性哈希");
        // null 内容按空文档处理
        assertEquals(empty.getIndexHash(), builder.build("doc5", null, null).getIndexHash());
        // 非法配置拒绝
        assertThrows(IllegalArgumentException.class, () -> new GraphIndexBuilder(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new GraphIndexBuilder(10, 10));
        assertThrows(IllegalArgumentException.class, () -> new GraphIndexBuilder(10, -1));
        assertThrows(IllegalArgumentException.class, () -> builder.build(" ", "内容", null));
    }
}
