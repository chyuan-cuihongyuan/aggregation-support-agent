package cn.chyuan.ai.infrastructure.neo4j;

import cn.chyuan.ai.domain.memory.entity.AgentEntityMemoryEntity;
import cn.chyuan.ai.domain.memory.entity.EntityGraph;
import cn.chyuan.ai.domain.memory.entity.EntityType;
import cn.chyuan.ai.domain.memory.entity.ExtractedEntity;
import cn.chyuan.ai.domain.memory.entity.graph.KnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.types.Relationship;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Neo4j 知识图谱仓储实现
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "neo4j.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class Neo4jKnowledgeGraphRepository implements KnowledgeGraphService {
    
    private final Driver neo4jDriver;
    
    @Override
    public void upsertEntity(AgentEntityMemoryEntity entity) {
        try (Session session = neo4jDriver.session()) {
            session.executeWrite(tx -> {
                String cypher = """
                    MERGE (e:Entity {entity_id: $entityId})
                    SET e.entity_name = $name
                    SET e.entity_type = $type
                    SET e.tenant_id = $tenantId
                    SET e.user_id = $userId
                    SET e.attributes = $attributes
                    SET e.updated_at = timestamp()
                    RETURN e
                    """;
                return tx.run(cypher, Values.parameters(
                    "entityId", entity.getEntityId(),
                    "name", entity.getEntityName(),
                    "type", entity.getEntityType().name(),
                    "tenantId", entity.getTenantId(),
                    "userId", entity.getUserId(),
                    "attributes", entity.getAttributes() != null ? entity.getAttributes().toString() : "{}"
                ));
            });
            log.debug("Neo4j upsert entity: {}", entity.getEntityId());
        } catch (Exception e) {
            log.error("Neo4j upsert entity failed: {}", entity.getEntityId(), e);
        }
    }
    
    @Override
    public void createRelation(String sourceEntityId, String relationType, String targetEntityId) {
        try (Session session = neo4jDriver.session()) {
            session.executeWrite(tx -> {
                String cypher = """
                    MATCH (a:Entity {entity_id: $sourceId})
                    MATCH (b:Entity {entity_id: $targetId})
                    MERGE (a)-[r:RELATION {relation_type: $relType}]->(b)
                    RETURN r
                    """;
                return tx.run(cypher, Values.parameters(
                    "sourceId", sourceEntityId,
                    "targetId", targetEntityId,
                    "relType", relationType
                ));
            });
            log.debug("Neo4j create relation: {} -[{}]-> {}", sourceEntityId, relationType, targetEntityId);
        } catch (Exception e) {
            log.error("Neo4j create relation failed", e);
        }
    }
    
    @Override
    public EntityGraph queryWithRelations(String entityId, int hops) {
        try (Session session = neo4jDriver.session()) {
            return session.executeRead(tx -> {
                // 查询中心实体
                String centerCypher = "MATCH (e:Entity {entity_id: $entityId}) RETURN e";
                Result centerResult = tx.run(centerCypher, Values.parameters("entityId", entityId));
                
                if (!centerResult.hasNext()) {
                    return EntityGraph.builder()
                        .centerEntity(null)
                        .relatedEntities(List.of())
                        .build();
                }
                
                var centerRecord = centerResult.next();
                var centerNode = centerRecord.get("e").asNode();
                AgentEntityMemoryEntity centerEntity = mapNodeToEntity(centerNode);
                
                // 多跳查询关联实体
                String relationCypher = String.format("""
                    MATCH (start:Entity {entity_id: $entityId})
                    -[rels*1..%d]-
                    (related:Entity)
                    RETURN start, rels, related
                    """, hops);
                
                Result relationResult = tx.run(relationCypher, Values.parameters("entityId", entityId));
                
                List<EntityGraph.RelatedEntity> relatedEntities = new ArrayList<>();
                while (relationResult.hasNext()) {
                    var record = relationResult.next();
                    var relatedNode = record.get("related").asNode();
                    var rels = record.get("rels").asList();
                    
                    // 取最后一个关系的信息
                    var lastRel = (Relationship) rels.get(rels.size() - 1);
                    String relType = lastRel.type();
                    String direction = lastRel.startNodeId() == centerNode.id() ? "OUTGOING" : "INCOMING";
                    
                    relatedEntities.add(EntityGraph.RelatedEntity.builder()
                        .relationType(relType)
                        .direction(direction)
                        .entity(mapNodeToEntity(relatedNode))
                        .build());
                }
                
                return EntityGraph.builder()
                    .centerEntity(centerEntity)
                    .relatedEntities(relatedEntities)
                    .build();
            });
        } catch (Exception e) {
            log.error("Neo4j query with relations failed: {}", entityId, e);
            return EntityGraph.builder()
                .centerEntity(null)
                .relatedEntities(List.of())
                .build();
        }
    }
    
    @Override
    public void ingestExtractedEntities(String tenantId, String userId, List<ExtractedEntity> entities) {
        for (ExtractedEntity extracted : entities) {
            if (extracted.getConfidence() < 0.7) {
                log.debug("跳过低置信度实体: {}", extracted.getName());
                continue;
            }
            
            // 创建实体节点
            String entityId = UUID.randomUUID().toString();
            AgentEntityMemoryEntity entity = AgentEntityMemoryEntity.builder()
                .entityId(entityId)
                .tenantId(tenantId)
                .userId(userId)
                .entityType(extracted.getType())
                .entityName(extracted.getName())
                .attributes(extracted.getAttributes())
                .confidence(extracted.getConfidence())
                .build();
            
            upsertEntity(entity);
            
            // 创建关系
            if (extracted.getRelations() != null) {
                for (var entry : extracted.getRelations().entrySet()) {
                    // 查找或创建目标实体
                    String targetName = entry.getValue();
                    String relType = entry.getKey();
                    
                    // 为目标创建临时节点
                    String targetEntityId = UUID.randomUUID().toString();
                    AgentEntityMemoryEntity targetEntity = AgentEntityMemoryEntity.builder()
                        .entityId(targetEntityId)
                        .tenantId(tenantId)
                        .userId(userId)
                        .entityType(EntityType.CONCEPT)
                        .entityName(targetName)
                        .attributes(Map.of())
                        .confidence(0.5)
                        .build();
                    upsertEntity(targetEntity);
                    
                    createRelation(entityId, relType, targetEntityId);
                }
            }
        }
    }
    
    @Override
    public void deleteEntity(String entityId) {
        try (Session session = neo4jDriver.session()) {
            session.executeWrite(tx -> {
                String cypher = "MATCH (e:Entity {entity_id: $entityId}) DETACH DELETE e";
                return tx.run(cypher, Values.parameters("entityId", entityId));
            });
            log.debug("Neo4j delete entity: {}", entityId);
        } catch (Exception e) {
            log.error("Neo4j delete entity failed: {}", entityId, e);
        }
    }
    
    private AgentEntityMemoryEntity mapNodeToEntity(org.neo4j.driver.types.Node node) {
        return AgentEntityMemoryEntity.builder()
            .entityId(node.get("entity_id").asString())
            .entityName(node.get("entity_name").asString())
            .entityType(EntityType.valueOf(node.get("entity_type").asString()))
            .tenantId(node.get("tenant_id").asString())
            .userId(node.get("user_id").asString())
            .build();
    }

    @Override
    public List<AgentEntityMemoryEntity> getAllEntities(String userId, String agentId) {
        try (Session session = neo4jDriver.session()) {
            return session.executeRead(tx -> {
                String cypher = "MATCH (e:Entity {user_id: $userId}) RETURN e";
                Result result = tx.run(cypher, Values.parameters("userId", userId));

                List<AgentEntityMemoryEntity> entities = new ArrayList<>();
                while (result.hasNext()) {
                    var record = result.next();
                    var node = record.get("e").asNode();
                    entities.add(mapNodeToEntity(node));
                }
                return entities;
            });
        } catch (Exception e) {
            log.error("Neo4j get all entities failed", e);
            return List.of();
        }
    }

    @Override
    public List<AgentEntityMemoryEntity> getRelatedEntities(String entityId, String userId, String agentId, int maxHops) {
        try (Session session = neo4jDriver.session()) {
            return session.executeRead(tx -> {
                String cypher = "MATCH path = (start:Entity {entity_id: $entityId, user_id: $userId})"
                    + "-[*1.." + maxHops + "]-(related:Entity)"
                    + " WHERE related.entity_id <> $entityId"
                    + " RETURN DISTINCT related";

                Result result = tx.run(cypher, Values.parameters(
                    "entityId", entityId,
                    "userId", userId
                ));

                List<AgentEntityMemoryEntity> entities = new ArrayList<>();
                while (result.hasNext()) {
                    var record = result.next();
                    var node = record.get("related").asNode();
                    entities.add(mapNodeToEntity(node));
                }
                return entities;
            });
        } catch (Exception e) {
            log.error("Neo4j get related entities failed", e);
            return List.of();
        }
    }
}
