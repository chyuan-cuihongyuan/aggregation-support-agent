package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.EntityAlignReportVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphEdgeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphNodeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.TextUnitVO;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphEntity;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphRelation;
import cn.chyuan.ai.domain.knowledgegraph.model.valobj.EntityExtractionResultVO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量索引合并器单测（工单 0311 AM6）：归一/别名/类型冲突/关系去重/幂等。
 */
class GraphIndexIncrementalMergerTest {

    private final GraphIndexBuilder builder = new GraphIndexBuilder(100, 10);
    private final GraphIndexIncrementalMerger merger = new GraphIndexIncrementalMerger();

    private GraphIndexVO baseIndex() {
        return builder.build("base", "第一篇讲 Spring Boot 与 Kafka 的组合。",
                EntityExtractionResultVO.builder()
                        .entities(Arrays.asList(
                                GraphEntity.builder().entityId("e1").entityName("Spring Boot").entityType("TECHNOLOGY").build(),
                                GraphEntity.builder().entityId("e2").entityName("Kafka").entityType("TECHNOLOGY").build()))
                        .relations(Arrays.asList(GraphRelation.builder()
                                .sourceEntityName("Spring Boot").targetEntityName("Kafka")
                                .relationType("USES").build()))
                        .build());
    }

    private GraphIndexVO incomingIndex() {
        return builder.build("doc2", "第二篇讲 SpringBoot 新版本与 RocketMQ 的迁移。",
                EntityExtractionResultVO.builder()
                        .entities(Arrays.asList(
                                GraphEntity.builder().entityId("e9").entityName("SpringBoot").entityType("TECHNOLOGY").build(),
                                GraphEntity.builder().entityId("e10").entityName("RocketMQ").entityType("TECHNOLOGY").build()))
                        .build());
    }

    @Test
    void 名称归一与别名表命中合并() {
        // "SpringBoot" 归一后即 "springboot" —— 与 "Spring Boot" 归一键相同 → EXACT 合并
        EntityAlignReportVO report = merger.align(baseIndex(), incomingIndex(), Collections.emptyMap());
        assertEquals("EXACT", report.getAlignments().get("springboot"));
        assertEquals("NONE", report.getAlignments().get("rocketmq"));
        assertEquals(1, report.getMerged());
        assertEquals(1, report.getCreated());
        assertTrue(report.getTypeConflicts().isEmpty());
    }

    @Test
    void 别名表重定向与合并幂等() {
        Map<String, String> alias = Map.of("SB", "Spring Boot");
        GraphIndexVO base = baseIndex();
        // 含别名 "SB" 的文档并入
        GraphIndexVO withAlias = builder.build("doc3", "SB 是惯例简称。",
                EntityExtractionResultVO.builder()
                        .entities(Collections.singletonList(
                                GraphEntity.builder().entityName("SB").entityType("TECHNOLOGY").build()))
                        .build());
        EntityAlignReportVO report = merger.align(base, withAlias, alias);
        assertEquals("ALIAS:springboot", report.getAlignments().get("sb"));
        GraphIndexVO merged = merger.merge(base, withAlias, alias);
        // 别名重定向 → 不新建实体
        assertEquals(base.getNodes().size(), merged.getNodes().size());
        // 幂等：重复导入同文档哈希不变
        assertEquals(merged.getIndexHash(), merger.merge(merged, withAlias, alias).getIndexHash());
    }

    @Test
    void 类型不一致不合并() {
        GraphIndexVO conflicting = builder.build("doc4", "同名不同型的 Kafka 品牌。",
                EntityExtractionResultVO.builder()
                        .entities(Collections.singletonList(
                                GraphEntity.builder().entityName("Kafka").entityType("PRODUCT").build()))
                        .build());
        EntityAlignReportVO report = merger.align(baseIndex(), conflicting, Collections.emptyMap());
        assertTrue(report.getTypeConflicts().contains("kafka"), "同键不同型应记录类型冲突");
    }

    @Test
    void 关系去重与来源追加() {
        GraphIndexVO base = baseIndex();
        // 重复导入同文档：边不翻倍、来源块不重复
        GraphIndexVO merged = merger.merge(base, base, Collections.emptyMap());
        assertEquals(base.getEdges().size(), merged.getEdges().size());
        assertEquals(base.getIndexHash(), merged.getIndexHash());
        // 新文档新关系边追加
        GraphIndexVO withEdge = builder.build("doc5", "RocketMQ 关联 Kafka 对比。",
                EntityExtractionResultVO.builder()
                        .entities(Arrays.asList(
                                GraphEntity.builder().entityName("RocketMQ").entityType("TECHNOLOGY").build(),
                                GraphEntity.builder().entityName("Kafka").entityType("TECHNOLOGY").build()))
                        .relations(Collections.singletonList(GraphRelation.builder()
                                .sourceEntityName("RocketMQ").targetEntityName("Kafka")
                                .relationType("COMPARE_TO").build()))
                        .build());
        GraphIndexVO merged2 = merger.merge(base, withEdge, Collections.emptyMap());
        assertEquals(base.getEdges().size() + 1, merged2.getEdges().size());
        assertEquals(base.getTextUnits().size() + 1, merged2.getTextUnits().size());
    }

    @Test
    void 节点与块结构完整() {
        GraphIndexVO merged = merger.merge(baseIndex(), incomingIndex(), Collections.emptyMap());
        // base 2 节点 + rocketmq 新增 = 3
        assertEquals(3, merged.getNodes().size());
        TextUnitVO first = merged.getTextUnits().get(0);
        assertEquals("base#u0", first.getUnitId());
        // base 1 块 + doc2 1 块 = 2
        assertEquals(2, merged.getTextUnits().size());
    }
}
