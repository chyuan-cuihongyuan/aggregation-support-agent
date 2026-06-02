package cn.chyuan.ai.domain.knowledgegraph.service;

import cn.chyuan.ai.domain.knowledgegraph.adapter.port.IEntityExtractionService;
import cn.chyuan.ai.domain.knowledgegraph.adapter.port.IGraphDatabaseService;
import cn.chyuan.ai.domain.knowledgegraph.adapter.repository.IExtractionTaskRepository;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.ExtractionTaskEntity;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphEntity;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphRelation;
import cn.chyuan.ai.domain.knowledgegraph.model.valobj.EntityExtractionResultVO;
import cn.chyuan.ai.domain.knowledgegraph.model.valobj.GraphSearchResultVO;
import cn.chyuan.ai.domain.knowledgegraph.model.valobj.SubgraphVO;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识图谱领域服务实现
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "knowledge-graph.enabled", havingValue = "true")
public class KnowledgeGraphService implements IKnowledgeGraphService {

    @Resource
    private IEntityExtractionService entityExtractionService;

    @Resource
    private IGraphDatabaseService graphDatabaseService;

    @Resource
    private IExtractionTaskRepository extractionTaskRepository;

    @Resource
    private IEmbeddingService embeddingService;

    @Override
    @Async
    public ExtractionTaskEntity buildGraphFromDocument(String documentId, List<DocumentChunkEntity> chunks) {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        ExtractionTaskEntity task = ExtractionTaskEntity.builder()
                .taskId(taskId)
                .documentId(documentId)
                .status("PROCESSING")
                .totalChunks(chunks.size())
                .processedChunks(0)
                .extractedEntities(0)
                .extractedRelations(0)
                .createdAt(new Date())
                .updatedAt(new Date())
                .build();
        extractionTaskRepository.save(task);

        try {
            // 确保 schema 存在
            graphDatabaseService.ensureSchema();

            // 用于去重：key = entityName + entityType
            Map<String, GraphEntity> entityMap = new LinkedHashMap<>();
            List<GraphRelation> allRelations = new ArrayList<>();

            // 分批处理，每批5个chunk
            int batchSize = 5;
            for (int i = 0; i < chunks.size(); i += batchSize) {
                int end = Math.min(i + batchSize, chunks.size());
                List<DocumentChunkEntity> batch = chunks.subList(i, end);

                List<String> texts = batch.stream().map(DocumentChunkEntity::getContent).collect(Collectors.toList());
                List<String> contexts = batch.stream()
                        .map(c -> {
                            // 取前后各一个chunk作为上下文
                            int idx = chunks.indexOf(c);
                            StringBuilder ctx = new StringBuilder();
                            if (idx > 0) ctx.append(chunks.get(idx - 1).getContent(), 0, Math.min(200, chunks.get(idx - 1).getContent().length()));
                            if (idx < chunks.size() - 1) ctx.append(" ... ").append(chunks.get(idx + 1).getContent(), 0, Math.min(200, chunks.get(idx + 1).getContent().length()));
                            return ctx.toString();
                        })
                        .collect(Collectors.toList());

                List<EntityExtractionResultVO> results = entityExtractionService.batchExtract(texts, contexts);

                int entityCount = 0;
                int relationCount = 0;
                for (EntityExtractionResultVO result : results) {
                    // 去重合并实体
                    for (GraphEntity entity : result.getEntities()) {
                        String key = entity.getEntityName() + "|" + entity.getEntityType();
                        entityMap.merge(key, entity, (existing, incoming) -> {
                            // 合并属性
                            if (incoming.getProperties() != null) {
                                if (existing.getProperties() == null) {
                                    existing.setProperties(new HashMap<>());
                                }
                                existing.getProperties().putAll(incoming.getProperties());
                            }
                            if (existing.getDescription() == null && incoming.getDescription() != null) {
                                existing.setDescription(incoming.getDescription());
                            }
                            return existing;
                        });
                        entityCount++;
                    }
                    allRelations.addAll(result.getRelations());
                    relationCount += result.getRelations().size();
                }

                // 更新任务进度
                extractionTaskRepository.updateStatus(taskId, "PROCESSING", end,
                        entityMap.size(), allRelations.size(), null);
            }

            // 计算实体嵌入向量并保存
            List<GraphEntity> uniqueEntities = new ArrayList<>(entityMap.values());
            for (GraphEntity entity : uniqueEntities) {
                String textForEmbedding = entity.getEntityName() + " " + (entity.getDescription() != null ? entity.getDescription() : "");
                entity.setEmbedding(embeddingService.embed(textForEmbedding));
                if (entity.getEntityId() == null) {
                    entity.setEntityId(UUID.randomUUID().toString().replace("-", ""));
                }
                entity.setCreatedAt(new Date());
                entity.setUpdatedAt(new Date());
            }

            graphDatabaseService.saveEntities(uniqueEntities);

            // 保存关系
            List<GraphRelation> validRelations = allRelations.stream()
                    .filter(r -> r.getSourceEntityId() != null && r.getTargetEntityId() != null)
                    .collect(Collectors.toList());
            for (GraphRelation relation : validRelations) {
                if (relation.getRelationId() == null) {
                    relation.setRelationId(UUID.randomUUID().toString().replace("-", ""));
                }
                relation.setCreatedAt(new Date());
            }
            graphDatabaseService.saveRelations(validRelations);

            // 更新任务完成
            extractionTaskRepository.updateStatus(taskId, "COMPLETED", chunks.size(),
                    uniqueEntities.size(), validRelations.size(), null);

            task.setStatus("COMPLETED");
            task.setExtractedEntities(uniqueEntities.size());
            task.setExtractedRelations(validRelations.size());
            log.info("图谱构建完成: documentId={}, entities={}, relations={}", documentId, uniqueEntities.size(), validRelations.size());

        } catch (Exception e) {
            log.error("图谱构建失败: documentId={}", documentId, e);
            extractionTaskRepository.updateStatus(taskId, "FAILED", task.getProcessedChunks(),
                    task.getExtractedEntities(), task.getExtractedRelations(), e.getMessage());
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
        }

        return task;
    }

    @Override
    public List<GraphEntity> searchEntities(String query, int topK) {
        float[] queryEmbedding = embeddingService.embed(query);
        return graphDatabaseService.findEntitiesByEmbedding(queryEmbedding, topK);
    }

    @Override
    public GraphSearchResultVO graphSearch(String query, int topK, int subgraphDepth) {
        List<GraphEntity> matchedEntities = searchEntities(query, topK);
        if (matchedEntities == null || matchedEntities.isEmpty()) {
            matchedEntities = fallbackTextSearchEntities(query, topK);
        }

        List<GraphRelation> allRelations = new ArrayList<>();
        List<GraphEntity> neighborEntities = new ArrayList<>();
        Set<String> visitedIds = matchedEntities.stream().map(GraphEntity::getEntityId).collect(Collectors.toSet());

        for (GraphEntity entity : matchedEntities) {
            SubgraphVO subgraph = graphDatabaseService.getSubgraph(entity.getEntityId(), subgraphDepth);
            if (subgraph != null) {
                subgraph.getEdges().forEach(edge -> {
                    // 简化：将边转为关系
                    GraphRelation relation = GraphRelation.builder()
                            .relationId(edge.getId())
                            .sourceEntityId(edge.getSource())
                            .targetEntityId(edge.getTarget())
                            .relationType(edge.getType())
                            .description(edge.getLabel())
                            .build();
                    allRelations.add(relation);
                });
                subgraph.getNodes().stream()
                        .filter(n -> !visitedIds.contains(n.getId()))
                        .forEach(n -> {
                            visitedIds.add(n.getId());
                            neighborEntities.add(GraphEntity.builder()
                                    .entityId(n.getId())
                                    .entityName(n.getLabel())
                                    .entityType(n.getType())
                                    .build());
                        });
            }
        }

        // 生成子图描述
        String subgraphDesc = matchedEntities.stream()
                .map(e -> e.getEntityName() + "(" + e.getEntityType() + ")")
                .collect(Collectors.joining(", "));

        return GraphSearchResultVO.builder()
                .matchedEntities(matchedEntities)
                .matchedRelations(allRelations)
                .neighborEntities(neighborEntities)
                .subgraphDescription(subgraphDesc)
                .score(matchedEntities.isEmpty() ? 0f : matchedEntities.get(0).getEmbedding() != null ? 1f : 0f)
                .build();
    }

    private List<GraphEntity> fallbackTextSearchEntities(String query, int topK) {
        try {
            LinkedHashMap<String, GraphEntity> entityMap = new LinkedHashMap<>();
            List<String> candidates = new ArrayList<>();
            if (query != null && !query.isBlank()) {
                candidates.add(query.trim());
                Arrays.stream(query.trim().split("\\s+"))
                        .filter(token -> token.length() > 1)
                        .forEach(candidates::add);
            }

            for (String candidate : candidates) {
                List<GraphEntity> entities = graphDatabaseService.findEntitiesByName(candidate);
                for (GraphEntity entity : entities) {
                    entityMap.putIfAbsent(entity.getEntityId(), entity);
                    if (entityMap.size() >= topK) {
                        return new ArrayList<>(entityMap.values());
                    }
                }
            }
            return new ArrayList<>(entityMap.values());
        } catch (Exception e) {
            log.warn("知识图谱文本检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public SubgraphVO getEntitySubgraph(String entityId, int depth) {
        return graphDatabaseService.getSubgraph(entityId, depth);
    }

    @Override
    public Map<String, Object> getGraphStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("entityCount", graphDatabaseService.getEntityCount());
        stats.put("relationCount", graphDatabaseService.getRelationCount());
        return stats;
    }

    @Override
    public boolean healthCheck() {
        return graphDatabaseService.healthCheck();
    }
}
