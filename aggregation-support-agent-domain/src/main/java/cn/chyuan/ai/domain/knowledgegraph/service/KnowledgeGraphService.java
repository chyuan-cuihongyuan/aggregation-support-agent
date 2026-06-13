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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
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

    /** 子图遍历专用的线程池，复用 RAG 检索线程池 */
    @Autowired(required = false)
    @Qualifier("ragRetrievalExecutor")
    private AsyncTaskExecutor graphExecutor;

    /**
     * 应用启动时恢复卡住的 PROCESSING 任务
     * 将之前因应用重启/崩溃而中断的任务标记为 FAILED，便于用户重试
     */
    @PostConstruct
    public void recoverStuckTasks() {
        try {
            List<ExtractionTaskEntity> stuckTasks = extractionTaskRepository.queryByStatus("PROCESSING");
            if (stuckTasks == null || stuckTasks.isEmpty()) {
                return;
            }
            log.warn("发现 {} 个卡在 PROCESSING 状态的图谱构建任务，正在标记为 FAILED 以便重试", stuckTasks.size());
            for (ExtractionTaskEntity task : stuckTasks) {
                String errorMsg = String.format("应用重启导致任务中断（已处理 %d/%d chunks，已抽取 %d 个实体），请通过重试接口重新构建",
                        task.getProcessedChunks(), task.getTotalChunks(), task.getExtractedEntities());
                extractionTaskRepository.updateStatus(task.getTaskId(), "FAILED",
                        task.getProcessedChunks(), task.getExtractedEntities(),
                        task.getExtractedRelations(), errorMsg);
                log.info("已标记任务为 FAILED: taskId={}, documentId={}, 已处理={}/{}",
                        task.getTaskId(), task.getDocumentId(), task.getProcessedChunks(), task.getTotalChunks());
            }
        } catch (Exception e) {
            log.error("恢复卡住任务失败: {}", e.getMessage(), e);
        }
    }

    @Override
    @Async("ragDocumentExecutor")
    public void buildGraphFromDocument(String documentId, List<DocumentChunkEntity> chunks) {
        // 检查是否已有任务（重试场景）
        ExtractionTaskEntity existingTask = extractionTaskRepository.queryByDocumentId(documentId);
        String taskId;
        if (existingTask != null && "FAILED".equals(existingTask.getStatus())) {
            // 重试已有失败任务
            taskId = existingTask.getTaskId();
            extractionTaskRepository.updateStatus(taskId, "PROCESSING", 0, 0, 0, null);
            log.info("重试图谱构建任务: taskId={}, documentId={}", taskId, documentId);
        } else {
            taskId = UUID.randomUUID().toString().replace("-", "");
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
        }

        int totalSavedEntities = 0;
        int totalSavedRelations = 0;

        try {
            // 确保 schema 存在
            graphDatabaseService.ensureSchema();

            // 用于去重：key = entityName + entityType
            Map<String, GraphEntity> entityMap = new LinkedHashMap<>();

            // 分批处理，每批5个chunk —— 每批次处理完立即保存到 Neo4j
            int batchSize = 5;
            for (int i = 0; i < chunks.size(); i += batchSize) {
                int end = Math.min(i + batchSize, chunks.size());
                List<DocumentChunkEntity> batch = chunks.subList(i, end);

                List<String> texts = batch.stream().map(DocumentChunkEntity::getContent).collect(Collectors.toList());
                List<String> contexts = batch.stream()
                        .map(c -> {
                            int idx = chunks.indexOf(c);
                            StringBuilder ctx = new StringBuilder();
                            if (idx > 0) ctx.append(chunks.get(idx - 1).getContent(), 0, Math.min(200, chunks.get(idx - 1).getContent().length()));
                            if (idx < chunks.size() - 1) ctx.append(" ... ").append(chunks.get(idx + 1).getContent(), 0, Math.min(200, chunks.get(idx + 1).getContent().length()));
                            return ctx.toString();
                        })
                        .collect(Collectors.toList());

                List<EntityExtractionResultVO> results = entityExtractionService.batchExtract(texts, contexts);

                // 收集本批次的实体和关系
                List<GraphRelation> batchRelations = new ArrayList<>();
                for (EntityExtractionResultVO result : results) {
                    for (GraphEntity entity : result.getEntities()) {
                        String key = entity.getEntityName() + "|" + entity.getEntityType();
                        entityMap.merge(key, entity, (existing, incoming) -> {
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
                    }
                    batchRelations.addAll(result.getRelations());
                }

                // ====== 关键改进：每批次立即计算嵌入并保存到 Neo4j ======
                List<GraphEntity> newEntities = entityMap.values().stream()
                        .filter(e -> e.getEntityId() == null) // 只处理尚未保存的实体
                        .collect(Collectors.toList());

                if (!newEntities.isEmpty()) {
                    for (GraphEntity entity : newEntities) {
                        String textForEmbedding = entity.getEntityName() + " " + (entity.getDescription() != null ? entity.getDescription() : "");
                        entity.setEmbedding(embeddingService.embed(textForEmbedding));
                        entity.setEntityId(UUID.randomUUID().toString().replace("-", ""));
                        entity.setSourceDocumentId(documentId);
                        entity.setCreatedAt(new Date());
                        entity.setUpdatedAt(new Date());
                    }
                    graphDatabaseService.saveEntities(newEntities);
                    totalSavedEntities += newEntities.size();
                    log.info("批次 {}/{} 保存实体: {} 个（累计 {} 个）", end, chunks.size(), newEntities.size(), totalSavedEntities);
                }

                // 保存本批次关系
                if (!batchRelations.isEmpty()) {
                    Map<String, String> entityIdByName = entityMap.values().stream()
                            .filter(e -> e.getEntityName() != null && e.getEntityId() != null)
                            .collect(Collectors.toMap(GraphEntity::getEntityName, GraphEntity::getEntityId, (left, right) -> left));
                    List<GraphRelation> validRelations = batchRelations.stream()
                            .peek(r -> resolveRelationEntityIds(r, entityIdByName, documentId))
                            .filter(r -> r.getSourceEntityId() != null && r.getTargetEntityId() != null)
                            .collect(Collectors.toList());
                    for (GraphRelation relation : validRelations) {
                        if (relation.getRelationId() == null) {
                            relation.setRelationId(UUID.randomUUID().toString().replace("-", ""));
                        }
                        relation.setCreatedAt(new Date());
                    }
                    if (!validRelations.isEmpty()) {
                        graphDatabaseService.saveRelations(validRelations);
                        totalSavedRelations += validRelations.size();
                    }
                }

                // 更新任务进度（已持久化到 Neo4j 的数量）
                extractionTaskRepository.updateStatus(taskId, "PROCESSING", end,
                        totalSavedEntities, totalSavedRelations, null);
            }

            // 更新任务完成
            extractionTaskRepository.updateStatus(taskId, "COMPLETED", chunks.size(),
                    totalSavedEntities, totalSavedRelations, null);

            log.info("图谱构建完成: documentId={}, entities={}, relations={}", documentId, totalSavedEntities, totalSavedRelations);

        } catch (Exception e) {
            log.error("图谱构建失败: documentId={}, 已保存实体={}, 已保存关系={}", documentId, totalSavedEntities, totalSavedRelations, e);
            extractionTaskRepository.updateStatus(taskId, "FAILED", 0,
                    totalSavedEntities, totalSavedRelations, e.getMessage());
        }
    }

    @Override
    public void retryTask(String documentId) {
        ExtractionTaskEntity task = extractionTaskRepository.queryByDocumentId(documentId);
        if (task == null) {
            throw new IllegalArgumentException("未找到文档对应的图谱构建任务: " + documentId);
        }
        if ("PROCESSING".equals(task.getStatus())) {
            throw new IllegalStateException("任务正在处理中，无法重试: " + task.getTaskId());
        }
        // 标记为 FAILED，等待下一次 buildGraphFromDocument 调用时自动重试
        extractionTaskRepository.updateStatus(task.getTaskId(), "FAILED", 0, 0, 0, "手动触发重试");
        log.info("已标记任务待重试: taskId={}, documentId={}", task.getTaskId(), documentId);
    }

    private void resolveRelationEntityIds(GraphRelation relation, Map<String, String> entityIdByName, String documentId) {
        if (relation.getSourceEntityId() == null && relation.getSourceEntityName() != null) {
            relation.setSourceEntityId(entityIdByName.get(relation.getSourceEntityName()));
        }
        if (relation.getTargetEntityId() == null && relation.getTargetEntityName() != null) {
            relation.setTargetEntityId(entityIdByName.get(relation.getTargetEntityName()));
        }
        if (relation.getSourceDocumentId() == null) {
            relation.setSourceDocumentId(documentId);
        }
        if (relation.getConfidence() == null) {
            relation.setConfidence(1.0f);
        }
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

        // 并行获取所有匹配实体的子图，带独立超时，避免串行遍历拖慢整体检索
        List<CompletableFuture<SubgraphVO>> subgraphFutures = matchedEntities.stream()
                .map(entity -> {
                    CompletableFuture<SubgraphVO> future = (graphExecutor != null)
                            ? CompletableFuture.supplyAsync(
                                () -> graphDatabaseService.getSubgraph(entity.getEntityId(), subgraphDepth),
                                graphExecutor)
                            : CompletableFuture.supplyAsync(
                                () -> graphDatabaseService.getSubgraph(entity.getEntityId(), subgraphDepth));
                    return future.orTimeout(5, TimeUnit.SECONDS).exceptionally(ex -> {
                        // 打印异常类名避免 TimeoutException 的 null message；降级跳过该子图，不阻断检索
                        log.warn("子图遍历超时或失败(已降级跳过): entityId={}, 异常={}", entity.getEntityId(), ex.getClass().getSimpleName());
                        return null;
                    });
                })
                .collect(Collectors.toList());

        // 等待全部完成（整体 5s 兜底超时，单条已由 orTimeout 控制）
        try {
            CompletableFuture.allOf(subgraphFutures.toArray(new CompletableFuture[0]))
                    .get(6, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("子图遍历整体超时，使用已完成的结果继续处理");
        } catch (Exception e) {
            log.warn("子图遍历等待异常: {}", e.getMessage());
        }

        // 收集所有已完成的子图结果
        for (CompletableFuture<SubgraphVO> future : subgraphFutures) {
            SubgraphVO subgraph = future.getNow(null);
            if (subgraph == null) {
                continue;
            }
            subgraph.getEdges().forEach(edge -> {
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
