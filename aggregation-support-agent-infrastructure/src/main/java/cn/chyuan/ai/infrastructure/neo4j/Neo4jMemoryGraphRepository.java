package cn.chyuan.ai.infrastructure.neo4j;

import cn.chyuan.ai.domain.memory.adapter.repository.IMemoryGraphRepository;
import cn.chyuan.ai.domain.memory.model.entity.MemoryRelation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.neo4j.driver.types.Relationship;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Neo4j 记忆图谱仓储实现
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "neo4j.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class Neo4jMemoryGraphRepository implements IMemoryGraphRepository {
    
    private final Driver neo4jDriver;
    
    @Override
    public void saveRelation(MemoryRelation relation) {
        try (Session session = neo4jDriver.session()) {
            session.executeWrite(tx -> {
                String cypher = """
                    MATCH (source:Memory {memory_id: $sourceMemoryId, tenant_id: $tenantId, user_id: $userId})
                    MATCH (target:Memory {memory_id: $targetMemoryId, tenant_id: $tenantId, user_id: $userId})
                    MERGE (source)-[r:RELATED {relation_id: $relationId}]->(target)
                    SET r.relation_type = $relationType,
                        r.strength = $strength,
                        r.description = $description,
                        r.created_at = $createdAt
                    RETURN r
                    """;
                
                return tx.run(cypher, Values.parameters(
                    "relationId", relation.getRelationId(),
                    "sourceMemoryId", relation.getSourceMemoryId(),
                    "targetMemoryId", relation.getTargetMemoryId(),
                    "tenantId", relation.getTenantId(),
                    "userId", relation.getUserId(),
                    "relationType", relation.getRelationType().name(),
                    "strength", relation.getStrength(),
                    "description", relation.getDescription(),
                    "createdAt", relation.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                ));
            });
            log.debug("Neo4j save memory relation: {} -> {}", relation.getSourceMemoryId(), relation.getTargetMemoryId());
        } catch (Exception e) {
            log.error("Neo4j save memory relation failed", e);
        }
    }
    
    @Override
    public void saveRelations(List<MemoryRelation> relations) {
        if (relations == null || relations.isEmpty()) {
            return;
        }
        
        try (Session session = neo4jDriver.session()) {
            session.executeWrite(tx -> {
                // 使用 UNWIND 批量写入，减少网络往返
                String cypher = """
                    UNWIND $relations as rel
                    MATCH (source:Memory {memory_id: rel.sourceMemoryId, tenant_id: rel.tenantId, user_id: rel.userId})
                    MATCH (target:Memory {memory_id: rel.targetMemoryId, tenant_id: rel.tenantId, user_id: rel.userId})
                    MERGE (source)-[r:RELATED {relation_id: rel.relationId}]->(target)
                    SET r.relation_type = rel.relationType,
                        r.strength = rel.strength,
                        r.description = rel.description,
                        r.created_at = rel.createdAt
                    RETURN count(r)
                    """;
                
                List<Map<String, Object>> params = relations.stream()
                    .map(r -> {
                        Map<String, Object> map = new java.util.HashMap<>();
                        map.put("relationId", r.getRelationId());
                        map.put("sourceMemoryId", r.getSourceMemoryId());
                        map.put("targetMemoryId", r.getTargetMemoryId());
                        map.put("tenantId", r.getTenantId());
                        map.put("userId", r.getUserId());
                        map.put("relationType", r.getRelationType().name());
                        map.put("strength", r.getStrength());
                        map.put("description", r.getDescription());
                        map.put("createdAt", r.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                        return map;
                    })
                    .collect(java.util.stream.Collectors.toList());
                
                return tx.run(cypher, Values.parameters("relations", params));
            });
            log.debug("Neo4j batch save {} memory relations", relations.size());
        } catch (Exception e) {
            log.error("Neo4j batch save memory relations failed", e);
        }
    }
    
    @Override
    public List<MemoryRelation> findRelations(String memoryId, String tenantId, String userId, int maxDepth) {
        final int effectiveMaxDepth = maxDepth <= 0 ? 1 : maxDepth;
        if (maxDepth <= 0) {
            log.warn("maxDepth 必须大于 0，当前值: {}，使用默认值 1", maxDepth);
        }
        
        try (Session session = neo4jDriver.session()) {
            return session.executeRead(tx -> {
                // 使用参数化查询避免 Cypher 注入风险
                String cypher = "MATCH path = (start:Memory {memory_id: $memoryId, tenant_id: $tenantId, user_id: $userId})"
                    + "-[*1.." + effectiveMaxDepth + "]-(related:Memory)"
                    + " WHERE related.memory_id <> $memoryId"
                    + " WITH relationships(path) as rels, nodes(path) as nodes"
                    + " UNWIND rels as rel"
                    + " WITH rel, nodes, rels"
                    + " WHERE size(rels) = 1 OR rel IN rels[0..1]"
                    + " RETURN DISTINCT rel, nodes[0] as startNode, nodes[size(nodes)-1] as endNode";
                
                Result result = tx.run(cypher, Values.parameters(
                    "memoryId", memoryId,
                    "tenantId", tenantId,
                    "userId", userId
                ));
                
                List<MemoryRelation> relations = new ArrayList<>();
                while (result.hasNext()) {
                    var record = result.next();
                    var rel = (Relationship) record.get("rel");
                    var startNode = record.get("startNode").asNode();
                    var endNode = record.get("endNode").asNode();
                    
                    // 安全获取属性值，避免 null 异常
                    String relationId = rel.get("relation_id").isNull() ? UUID.randomUUID().toString() : rel.get("relation_id").asString();
                    String relationTypeStr = rel.get("relation_type").isNull() ? "SIMILAR" : rel.get("relation_type").asString();
                    double strength = rel.get("strength").isNull() ? 0.5 : rel.get("strength").asDouble();
                    String description = rel.get("description").isNull() ? "" : rel.get("description").asString();
                    long createdAtMillis = rel.get("created_at").isNull() ? System.currentTimeMillis() : rel.get("created_at").asLong();
                    
                    relations.add(MemoryRelation.builder()
                        .relationId(relationId)
                        .sourceMemoryId(startNode.get("memory_id").asString())
                        .targetMemoryId(endNode.get("memory_id").asString())
                        .relationType(MemoryRelation.RelationType.valueOf(relationTypeStr))
                        .strength(strength)
                        .description(description)
                        .tenantId(tenantId)
                        .userId(userId)
                        .createdAt(LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(createdAtMillis),
                            ZoneId.systemDefault()
                        ))
                        .build());
                }
                return relations;
            });
        } catch (Exception e) {
            log.error("Neo4j find memory relations failed: {}", memoryId, e);
            return List.of();
        }
    }
    
    @Override
    public List<String> findRelatedMemoryIds(String memoryId, String tenantId, String userId) {
        try (Session session = neo4jDriver.session()) {
            return session.executeRead(tx -> {
                String cypher = """
                    MATCH (start:Memory {memory_id: $memoryId, tenant_id: $tenantId, user_id: $userId})
                    -[r:RELATED]-(related:Memory)
                    RETURN DISTINCT related.memory_id as relatedMemoryId
                    """;
                
                Result result = tx.run(cypher, Values.parameters(
                    "memoryId", memoryId,
                    "tenantId", tenantId,
                    "userId", userId
                ));
                
                List<String> relatedIds = new ArrayList<>();
                while (result.hasNext()) {
                    var record = result.next();
                    relatedIds.add(record.get("relatedMemoryId").asString());
                }
                return relatedIds;
            });
        } catch (Exception e) {
            log.error("Neo4j find related memory ids failed: {}", memoryId, e);
            return List.of();
        }
    }
    
    @Override
    public void deleteRelations(String memoryId, String tenantId, String userId) {
        try (Session session = neo4jDriver.session()) {
            session.executeWrite(tx -> {
                String cypher = """
                    MATCH (m:Memory {memory_id: $memoryId, tenant_id: $tenantId, user_id: $userId})
                    -[r:RELATED]-()
                    DELETE r
                    """;
                return tx.run(cypher, Values.parameters(
                    "memoryId", memoryId,
                    "tenantId", tenantId,
                    "userId", userId
                ));
            });
            log.debug("Neo4j delete memory relations: {}", memoryId);
        } catch (Exception e) {
            log.error("Neo4j delete memory relations failed: {}", memoryId, e);
        }
    }
    
    @Override
    public void deleteRelation(String relationId) {
        try (Session session = neo4jDriver.session()) {
            session.executeWrite(tx -> {
                String cypher = "MATCH ()-[r:RELATED {relation_id: $relationId}]->() DELETE r";
                return tx.run(cypher, Values.parameters("relationId", relationId));
            });
            log.debug("Neo4j delete relation: {}", relationId);
        } catch (Exception e) {
            log.error("Neo4j delete relation failed: {}", relationId, e);
        }
    }
    
    @Override
    public boolean existsMemory(String memoryId, String tenantId, String userId) {
        try (Session session = neo4jDriver.session()) {
            return session.executeRead(tx -> {
                String cypher = "MATCH (m:Memory {memory_id: $memoryId, tenant_id: $tenantId, user_id: $userId}) RETURN count(m) > 0 as exists";
                Result result = tx.run(cypher, Values.parameters("memoryId", memoryId, "tenantId", tenantId, "userId", userId));
                return result.hasNext() && result.next().get("exists").asBoolean();
            });
        } catch (Exception e) {
            log.error("Neo4j check memory exists failed: {}", memoryId, e);
            return false;
        }
    }
    
    @Override
    public long countRelations(String tenantId, String userId) {
        try (Session session = neo4jDriver.session()) {
            return session.executeRead(tx -> {
                // 使用有向匹配避免重复计数
                String cypher = """
                    MATCH (:Memory {tenant_id: $tenantId, user_id: $userId})-[r:RELATED]->(:Memory {tenant_id: $tenantId, user_id: $userId})
                    RETURN count(DISTINCT r) as count
                    """;
                Result result = tx.run(cypher, Values.parameters(
                    "tenantId", tenantId,
                    "userId", userId
                ));
                return result.hasNext() ? result.next().get("count").asLong() : 0;
            });
        } catch (Exception e) {
            log.error("Neo4j count relations failed", e);
            return 0;
        }
    }
}
