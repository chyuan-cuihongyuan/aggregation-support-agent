package cn.chyuan.ai.infrastructure.gateway.graph;

import cn.chyuan.ai.domain.knowledgegraph.adapter.port.IGraphDatabaseService;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphEntity;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphRelation;
import cn.chyuan.ai.domain.knowledgegraph.model.valobj.SubgraphVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Neo4j 图数据库服务实现
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "neo4j.enabled", havingValue = "true")
public class Neo4jGraphDatabaseService implements IGraphDatabaseService {

    @Resource
    private Neo4jClient neo4jClient;

    @Resource
    private Driver neo4jDriver;

    @Override
    public void ensureSchema() {
        try (Session session = neo4jDriver.session()) {
            // 创建唯一约束
            session.run("CREATE CONSTRAINT IF NOT EXISTS FOR (e:Entity) REQUIRE e.entityId IS UNIQUE");
            // 创建向量索引（Neo4j 5.x）
            session.run("CREATE VECTOR INDEX entity_embedding IF NOT EXISTS FOR (e:Entity) ON (e.embedding) OPTIONS {indexConfig: {`vector.dimensions`: 1024, `vector.similarity_function`: 'cosine'}}");
            log.info("Neo4j schema 初始化完成");
        } catch (Exception e) {
            log.warn("Schema 初始化警告: {}", e.getMessage());
        }
    }

    @Override
    public String saveEntity(GraphEntity entity) {
        neo4jClient.query("""
                MERGE (e:Entity {entityId: $entityId})
                SET e.name = $name, e.type = $type, e.description = $description,
                    e.properties = $properties, e.sourceDocumentId = $sourceDocumentId,
                    e.sourceChunkId = $sourceChunkId, e.embedding = $embedding,
                    e.updatedAt = datetime()
                ON CREATE SET e.createdAt = datetime()
                """)
                .bind(entity.getEntityId()).to("entityId")
                .bind(entity.getEntityName()).to("name")
                .bind(entity.getEntityType()).to("type")
                .bind(entity.getDescription()).to("description")
                .bind(entity.getProperties() != null ? entity.getProperties() : Collections.emptyMap()).to("properties")
                .bind(entity.getSourceDocumentId()).to("sourceDocumentId")
                .bind(entity.getSourceChunkId()).to("sourceChunkId")
                .bind(entity.getEmbedding() != null ? Arrays.stream(entity.getEmbedding()).boxed().collect(Collectors.toList()) : Collections.emptyList()).to("embedding")
                .run();
        return entity.getEntityId();
    }

    @Override
    public void saveEntities(List<GraphEntity> entities) {
        entities.forEach(this::saveEntity);
    }

    @Override
    public String saveRelation(GraphRelation relation) {
        neo4jClient.query("""
                MATCH (s:Entity {entityId: $sourceId})
                MATCH (t:Entity {entityId: $targetId})
                MERGE (s)-[r:RELATES_TO {relationId: $relationId}]->(t)
                SET r.type = $type, r.description = $description,
                    r.confidence = $confidence, r.sourceDocumentId = $sourceDocumentId,
                    r.createdAt = datetime()
                """)
                .bind(relation.getSourceEntityId()).to("sourceId")
                .bind(relation.getTargetEntityId()).to("targetId")
                .bind(relation.getRelationId()).to("relationId")
                .bind(relation.getRelationType()).to("type")
                .bind(relation.getDescription()).to("description")
                .bind(relation.getConfidence()).to("confidence")
                .bind(relation.getSourceDocumentId()).to("sourceDocumentId")
                .run();
        return relation.getRelationId();
    }

    @Override
    public void saveRelations(List<GraphRelation> relations) {
        relations.forEach(this::saveRelation);
    }

    @Override
    public List<GraphEntity> findEntitiesByName(String name) {
        return neo4jClient.query("MATCH (e:Entity) WHERE e.name CONTAINS $name RETURN e")
                .bind(name).to("name")
                .fetchAs(GraphEntity.class)
                .mappedBy((typeSystem, record) -> mapRecordToEntity(record))
                .all()
                .stream().collect(Collectors.toList());
    }

    @Override
    public List<GraphEntity> findEntitiesByType(String type, int limit) {
        return neo4jClient.query("MATCH (e:Entity {type: $type}) RETURN e LIMIT $limit")
                .bind(type).to("type")
                .bind((long) limit).to("limit")
                .fetchAs(GraphEntity.class)
                .mappedBy((typeSystem, record) -> mapRecordToEntity(record))
                .all()
                .stream().collect(Collectors.toList());
    }

    @Override
    public List<GraphEntity> findEntitiesByEmbedding(float[] embedding, int topK) {
        List<Float> embeddingList = Arrays.stream(embedding).boxed().collect(Collectors.toList());
        try {
            return neo4jClient.query("""
                    CALL db.index.vector.queryNodes('entity_embedding', $topK, $embedding)
                    YIELD node, score
                    RETURN node, score
                    """)
                    .bind((long) topK).to("topK")
                    .bind(embeddingList).to("embedding")
                    .fetchAs(GraphEntity.class)
                    .mappedBy((typeSystem, record) -> {
                        var node = record.get("node").asNode();
                        float score = record.get("score").asFloat();
                        GraphEntity entity = mapNodeToEntity(node);
                        // 将分数暂存在 properties 中
                        if (entity.getProperties() == null) entity.setProperties(new HashMap<>());
                        return entity;
                    })
                    .all()
                    .stream().collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("向量索引搜索失败，降级为文本搜索: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public SubgraphVO getSubgraph(String entityId, int depth) {
        Collection<SubgraphVO.GraphNodeVO> nodes = neo4jClient.query("""
                MATCH (e:Entity {entityId: $entityId})-[r*1..""" + depth + "]-(n:Entity) " + """
                RETURN DISTINCT n
                """)
                .bind(entityId).to("entityId")
                .fetchAs(SubgraphVO.GraphNodeVO.class)
                .mappedBy((ts, record) -> {
                    var node = record.get("n").asNode();
                    return SubgraphVO.GraphNodeVO.builder()
                            .id(node.get("entityId").asString())
                            .label(node.get("name").asString())
                            .type(node.get("type").asString())
                            .build();
                })
                .all();

        // 添加中心节点
        Collection<SubgraphVO.GraphNodeVO> centerNodes = neo4jClient.query("MATCH (e:Entity {entityId: $entityId}) RETURN e")
                .bind(entityId).to("entityId")
                .fetchAs(SubgraphVO.GraphNodeVO.class)
                .mappedBy((ts, record) -> {
                    var node = record.get("e").asNode();
                    return SubgraphVO.GraphNodeVO.builder()
                            .id(node.get("entityId").asString())
                            .label(node.get("name").asString())
                            .type(node.get("type").asString())
                            .build();
                })
                .all();

        Set<String> nodeIds = new HashSet<>();
        List<SubgraphVO.GraphNodeVO> allNodes = new ArrayList<>();
        for (var n : centerNodes) { nodeIds.add(n.getId()); allNodes.add(n); }
        for (var n : nodes) { if (nodeIds.add(n.getId())) allNodes.add(n); }

        // 获取节点间的关系
        List<String> nodeIdList = allNodes.stream().map(SubgraphVO.GraphNodeVO::getId).collect(Collectors.toList());
        List<SubgraphVO.GraphEdgeVO> edges = new ArrayList<>();
        if (!nodeIdList.isEmpty()) {
            try {
                var edgeResults = neo4jClient.query("""
                        MATCH (s:Entity)-[r:RELATES_TO]->(t:Entity)
                        WHERE s.entityId IN $ids AND t.entityId IN $ids
                        RETURN s.entityId AS source, t.entityId AS target, r.type AS type, r.description AS desc, elementId(r) AS rid
                        """)
                        .bind(nodeIdList).to("ids")
                        .fetch()
                        .all();
                for (var row : edgeResults) {
                    edges.add(SubgraphVO.GraphEdgeVO.builder()
                            .id(String.valueOf(row.get("rid")))
                            .source(String.valueOf(row.get("source")))
                            .target(String.valueOf(row.get("target")))
                            .label(String.valueOf(row.get("desc") != null ? row.get("desc") : row.get("type")))
                            .type(String.valueOf(row.get("type")))
                            .build());
                }
            } catch (Exception e) {
                log.warn("获取关系失败: {}", e.getMessage());
            }
        }

        return SubgraphVO.builder().nodes(allNodes).edges(edges).build();
    }

    @Override
    public SubgraphVO searchSubgraph(String query, int topK, int depth) {
        // 简化实现：先找名称匹配的实体，再获取子图
        List<GraphEntity> entities = findEntitiesByName(query);
        if (entities.isEmpty()) return SubgraphVO.builder().nodes(Collections.emptyList()).edges(Collections.emptyList()).build();
        return getSubgraph(entities.get(0).getEntityId(), depth);
    }

    @Override
    public long getEntityCount() {
        try {
            return neo4jClient.query("MATCH (e:Entity) RETURN count(e) AS cnt")
                    .fetchAs(Long.class)
                    .mappedBy((ts, record) -> record.get("cnt").asLong())
                    .one()
                    .orElse(0L);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public long getRelationCount() {
        try {
            return neo4jClient.query("MATCH ()-[r:RELATES_TO]->() RETURN count(r) AS cnt")
                    .fetchAs(Long.class)
                    .mappedBy((ts, record) -> record.get("cnt").asLong())
                    .one()
                    .orElse(0L);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public boolean healthCheck() {
        try (Session session = neo4jDriver.session()) {
            session.run("RETURN 1");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private GraphEntity mapRecordToEntity(Record record) {
        var node = record.get(0).asNode();
        return mapNodeToEntity(node);
    }

    private GraphEntity mapNodeToEntity(org.neo4j.driver.types.Node node) {
        return GraphEntity.builder()
                .entityId(node.get("entityId").asString(""))
                .entityName(node.get("name").asString(""))
                .entityType(node.get("type").asString(""))
                .description(node.get("description").asString(null))
                .sourceDocumentId(node.get("sourceDocumentId").asString(null))
                .sourceChunkId(node.get("sourceChunkId").asString(null))
                .build();
    }
}
