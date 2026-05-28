package cn.chyuan.ai.infrastructure.adapter.repository.memory;

import cn.chyuan.ai.domain.memory.adapter.repository.IAgentMemoryRepository;
import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryEntry;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.infrastructure.config.MilvusConfigProperties;
import cn.chyuan.ai.infrastructure.persistent.mapper.memory.AgentMemoryMapper;
import cn.chyuan.ai.infrastructure.dao.po.memory.AgentMemoryPO;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent 记忆 Milvus 仓储实现
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true", matchIfMissing = false)
public class AgentMemoryMilvusRepository implements IAgentMemoryRepository {
    
    /** Agent Memory 集合名称 */
    private static final String COLLECTION_NAME = "agent_memory";
    
    /** 字段名称常量 */
    private static final String FIELD_ID = "memory_id";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_TENANT_ID = "tenant_id";
    private static final String FIELD_USER_ID = "user_id";
    private static final String FIELD_AGENT_ID = "agent_id";
    private static final String FIELD_SCOPE = "scope";
    private static final String FIELD_MEMORY_TYPE = "memory_type";
    private static final String FIELD_IMPORTANCE = "importance";
    private static final String FIELD_CONTENT_HASH = "content_hash";
    private static final String FIELD_CREATED_AT = "created_at";
    
    /** 搜索参数 */
    private static final String SEARCH_PARAMS = "{\"nprobe\": 128}";
    
    @Resource
    private MilvusServiceClient milvusServiceClient;
    
    @Resource
    private MilvusConfigProperties milvusConfigProperties;
    
    @Resource
    private IEmbeddingService embeddingService;
    
    @Resource
    private AgentMemoryMapper agentMemoryMapper;
    
    private final Gson gson = new Gson();
    
    /**
     * 初始化：确保集合存在
     */
    @PostConstruct
    public void init() {
        try {
            ensureCollection();
        } catch (Exception e) {
            log.error("初始化 Agent Memory 集合失败", e);
        }
    }
    
    /**
     * 确保集合存在
     */
    private void ensureCollection() {
        try {
            // 检查集合是否已存在
            R<Boolean> hasCollection = milvusServiceClient.hasCollection(
                HasCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .build()
            );
            
            if (hasCollection.getData() == Boolean.TRUE) {
                log.info("Agent Memory 集合已存在: {}", COLLECTION_NAME);
                loadCollection();
                return;
            }
            
            // 定义集合 Schema
            FieldType idField = FieldType.newBuilder()
                .withName(FIELD_ID)
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .withPrimaryKey(true)
                .build();
            
            FieldType vectorField = FieldType.newBuilder()
                .withName(FIELD_VECTOR)
                .withDataType(DataType.FloatVector)
                .withDimension(milvusConfigProperties.getDimension())
                .build();
            
            FieldType contentField = FieldType.newBuilder()
                .withName(FIELD_CONTENT)
                .withDataType(DataType.VarChar)
                .withMaxLength(65535)
                .build();
            
            FieldType tenantIdField = FieldType.newBuilder()
                .withName(FIELD_TENANT_ID)
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .build();
            
            FieldType userIdField = FieldType.newBuilder()
                .withName(FIELD_USER_ID)
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .build();
            
            FieldType agentIdField = FieldType.newBuilder()
                .withName(FIELD_AGENT_ID)
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .build();
            
            FieldType scopeField = FieldType.newBuilder()
                .withName(FIELD_SCOPE)
                .withDataType(DataType.VarChar)
                .withMaxLength(255)
                .build();
            
            FieldType memoryTypeField = FieldType.newBuilder()
                .withName(FIELD_MEMORY_TYPE)
                .withDataType(DataType.VarChar)
                .withMaxLength(32)
                .build();
            
            FieldType importanceField = FieldType.newBuilder()
                .withName(FIELD_IMPORTANCE)
                .withDataType(DataType.Float)
                .build();
            
            FieldType contentHashField = FieldType.newBuilder()
                .withName(FIELD_CONTENT_HASH)
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .build();
            
            FieldType createdAtField = FieldType.newBuilder()
                .withName(FIELD_CREATED_AT)
                .withDataType(DataType.Int64)
                .build();
            
            // 创建集合
            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withDescription("Agent 记忆向量存储集合")
                .withShardsNum(2)
                .addFieldType(idField)
                .addFieldType(vectorField)
                .addFieldType(contentField)
                .addFieldType(tenantIdField)
                .addFieldType(userIdField)
                .addFieldType(agentIdField)
                .addFieldType(scopeField)
                .addFieldType(memoryTypeField)
                .addFieldType(importanceField)
                .addFieldType(contentHashField)
                .addFieldType(createdAtField)
                .build();
            
            R<RpcStatus> createResult = milvusServiceClient.createCollection(createParam);
            if (createResult.getStatus() != R.Status.Success.getCode()) {
                log.error("创建 Agent Memory 集合失败: {}", createResult.getMessage());
                return;
            }
            log.info("Agent Memory 集合创建成功: {}", COLLECTION_NAME);
            
            // 创建索引 (使用 HNSW 索引，更适合语义检索)
            CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withFieldName(FIELD_VECTOR)
                .withIndexType(io.milvus.param.IndexType.HNSW)
                .withMetricType(io.milvus.param.MetricType.COSINE)
                .withExtraParam("{\"M\": 16, \"efConstruction\": 200}")
                .build();
            
            R<RpcStatus> indexResult = milvusServiceClient.createIndex(indexParam);
            if (indexResult.getStatus() != R.Status.Success.getCode()) {
                log.error("创建 Agent Memory 索引失败: {}", indexResult.getMessage());
                return;
            }
            log.info("Agent Memory 索引创建成功: HNSW");
            
            // 加载集合
            loadCollection();
            
        } catch (Exception e) {
            log.error("确保 Agent Memory 集合存在时发生异常", e);
            throw new RuntimeException("Agent Memory 集合初始化失败", e);
        }
    }
    
    /**
     * 加载集合到内存
     */
    private void loadCollection() {
        try {
            R<RpcStatus> loadResult = milvusServiceClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .build()
            );
            if (loadResult.getStatus() == R.Status.Success.getCode()) {
                log.info("Agent Memory 集合加载成功: {}", COLLECTION_NAME);
            }
        } catch (Exception e) {
            log.warn("加载 Agent Memory 集合时发生异常", e);
        }
    }
    
    @Override
    public void save(AgentMemoryEntity entity) {
        // 保存到 MySQL
        AgentMemoryPO po = convertToPO(entity);
        agentMemoryMapper.insert(po);
    }
    
    @Override
    public void saveBatch(List<AgentMemoryEntity> entities) {
        for (AgentMemoryEntity entity : entities) {
            save(entity);
        }
    }
    
    @Override
    public AgentMemoryEntity findByMemoryId(String memoryId) {
        AgentMemoryPO po = agentMemoryMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentMemoryPO>()
                .eq(AgentMemoryPO::getMemoryId, memoryId)
                .eq(AgentMemoryPO::getStatus, 1)
        );
        return po != null ? convertToEntity(po) : null;
    }
    
    @Override
    public boolean existsByContentHash(String contentHash, String tenantId, String userId) {
        return agentMemoryMapper.countByContentHash(contentHash, tenantId, userId) > 0;
    }
    
    @Override
    public List<AgentMemoryEntity> findByTenantAndUser(String tenantId, String userId) {
        List<AgentMemoryPO> poList = agentMemoryMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentMemoryPO>()
                .eq(AgentMemoryPO::getTenantId, tenantId)
                .eq(AgentMemoryPO::getUserId, userId)
                .eq(AgentMemoryPO::getStatus, 1)
                .orderByDesc(AgentMemoryPO::getCreatedAt)
        );
        return poList.stream().map(this::convertToEntity).collect(Collectors.toList());
    }
    
    @Override
    public List<AgentMemoryEntity> findByScope(String scope) {
        List<AgentMemoryPO> poList = agentMemoryMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentMemoryPO>()
                .likeRight(AgentMemoryPO::getScope, scope)
                .eq(AgentMemoryPO::getStatus, 1)
                .orderByDesc(AgentMemoryPO::getCreatedAt)
        );
        return poList.stream().map(this::convertToEntity).collect(Collectors.toList());
    }
    
    @Override
    public void updateContent(String memoryId, String content, String contentHash) {
        agentMemoryMapper.updateContent(memoryId, content, contentHash);
    }
    
    @Override
    public void softDelete(String memoryId) {
        agentMemoryMapper.softDelete(memoryId);
        // 同时删除 Milvus 中的向量
        deleteEmbedding(memoryId);
    }
    
    @Override
    public int deleteExpired(String tenantId, String userId) {
        return agentMemoryMapper.deleteExpired(tenantId, userId);
    }
    
    @Override
    public List<MemoryEntry> searchSimilar(String content, String tenantId, String userId, int limit) {
        // 获取内容的向量
        float[] embedding = embeddingService.embed(content);
        
        // 在 Milvus 中搜索
        List<AgentMemoryEntity> results = search(embedding, tenantId, userId, null, limit);
        
        // 计算相似度分数
        return results.stream()
            .map(entry -> {
                float[] entryEmbedding = getEmbedding(entry.getMemoryId());
                double similarity = cosineSimilarity(embedding, entryEmbedding);
                return MemoryEntry.builder()
                    .entry(entry)
                    .score(similarity)
                    .build();
            })
            .filter(e -> e.getScore() > 0.7)
            .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
            .collect(Collectors.toList());
    }
    
    @Override
    public List<AgentMemoryEntity> search(float[] queryEmbedding, String tenantId, String userId,
                                          String scope, int limit) {
        try {
            // 构建过滤条件
            StringBuilder filter = new StringBuilder();
            filter.append(FIELD_TENANT_ID).append(" == \"").append(tenantId).append("\"");
            filter.append(" && ").append(FIELD_USER_ID).append(" == \"").append(userId).append("\"");
            if (scope != null && !scope.isEmpty()) {
                filter.append(" && ").append(FIELD_SCOPE).append(" like \"").append(scope).append("%\"");
            }
            
            // 构建搜索参数
            SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withVectors(List.of(Arrays.stream(queryEmbedding).boxed().collect(Collectors.toList())))
                .withVectorFieldName(FIELD_VECTOR)
                .withTopK(limit)
                .withParams(SEARCH_PARAMS)
                .withExpr(filter.toString())
                .withOutFields(List.of(
                    FIELD_ID, FIELD_CONTENT, FIELD_TENANT_ID, FIELD_USER_ID,
                    FIELD_AGENT_ID, FIELD_SCOPE, FIELD_MEMORY_TYPE, FIELD_IMPORTANCE,
                    FIELD_CONTENT_HASH, FIELD_CREATED_AT
                ))
                .build();
            
            // 执行搜索
            R<SearchResults> searchResult = milvusServiceClient.search(searchParam);
            if (searchResult.getStatus() != R.Status.Success.getCode()) {
                log.error("Agent Memory 搜索失败: {}", searchResult.getMessage());
                return List.of();
            }
            
            // 解析结果
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResult.getData().getResults());
            List<AgentMemoryEntity> results = new ArrayList<>();
            
            for (int i = 0; i < wrapper.getRowCount(0); i++) {
                SearchResultsWrapper.IDScore score = wrapper.getIDScore(0, i);
                Map<String, Object> fieldValues = score.getFieldValues();
                
                AgentMemoryEntity entity = AgentMemoryEntity.builder()
                    .memoryId((String) fieldValues.get(FIELD_ID))
                    .content((String) fieldValues.get(FIELD_CONTENT))
                    .tenantId((String) fieldValues.get(FIELD_TENANT_ID))
                    .userId((String) fieldValues.get(FIELD_USER_ID))
                    .agentId((String) fieldValues.get(FIELD_AGENT_ID))
                    .scope((String) fieldValues.get(FIELD_SCOPE))
                    .memoryType(MemoryType.valueOf((String) fieldValues.get(FIELD_MEMORY_TYPE)))
                    .importance(((Number) fieldValues.get(FIELD_IMPORTANCE)).floatValue())
                    .contentHash((String) fieldValues.get(FIELD_CONTENT_HASH))
                    .createdAt(Instant.ofEpochMilli(((Number) fieldValues.get(FIELD_CREATED_AT)).longValue()))
                    .build();
                
                results.add(entity);
            }
            
            return results;
            
        } catch (Exception e) {
            log.error("Agent Memory 搜索异常", e);
            return List.of();
        }
    }
    
    @Override
    public float[] getEmbedding(String memoryId) {
        try {
            // 从 MySQL 获取实体
            AgentMemoryEntity entity = findByMemoryId(memoryId);
            if (entity == null) {
                return new float[0];
            }
            
            // 重新生成 embedding（因为 Milvus 不支持直接查询向量）
            return embeddingService.embed(entity.getContent());
        } catch (Exception e) {
            log.error("获取记忆向量失败: {}", memoryId, e);
            return new float[0];
        }
    }
    
    @Override
    public void insertWithEmbedding(AgentMemoryEntity entity, float[] embedding) {
        try {
            // 构建插入数据
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field(FIELD_ID, List.of(entity.getMemoryId())));
            fields.add(new InsertParam.Field(FIELD_VECTOR, 
                List.of(Arrays.stream(embedding).boxed().collect(Collectors.toList()))));
            fields.add(new InsertParam.Field(FIELD_CONTENT, List.of(entity.getContent())));
            fields.add(new InsertParam.Field(FIELD_TENANT_ID, List.of(entity.getTenantId())));
            fields.add(new InsertParam.Field(FIELD_USER_ID, List.of(entity.getUserId())));
            fields.add(new InsertParam.Field(FIELD_AGENT_ID, List.of(entity.getAgentId() != null ? entity.getAgentId() : "")));
            fields.add(new InsertParam.Field(FIELD_SCOPE, List.of(entity.getScope())));
            fields.add(new InsertParam.Field(FIELD_MEMORY_TYPE, List.of(entity.getMemoryType().name())));
            fields.add(new InsertParam.Field(FIELD_IMPORTANCE, List.of(entity.getImportance())));
            fields.add(new InsertParam.Field(FIELD_CONTENT_HASH, List.of(entity.getContentHash())));
            fields.add(new InsertParam.Field(FIELD_CREATED_AT, List.of(entity.getCreatedAt().toEpochMilli())));
            
            InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withFields(fields)
                .build();
            
            R<MutationResult> insertResult = milvusServiceClient.insert(insertParam);
            if (insertResult.getStatus() != R.Status.Success.getCode()) {
                log.error("Agent Memory 向量插入失败: {}", insertResult.getMessage());
                throw new RuntimeException("Agent Memory 向量插入失败: " + insertResult.getMessage());
            }
            
            // 刷新数据
            milvusServiceClient.flush(FlushParam.newBuilder()
                .withCollectionNames(List.of(COLLECTION_NAME))
                .build());
            
            log.debug("Agent Memory 向量插入成功: {}", entity.getMemoryId());
            
        } catch (Exception e) {
            log.error("Agent Memory 向量插入异常", e);
            throw new RuntimeException("Agent Memory 向量插入失败", e);
        }
    }
    
    @Override
    public void updateEmbedding(String memoryId, float[] embedding) {
        // Milvus 不支持直接更新向量，需要删除后重新插入
        deleteEmbedding(memoryId);
        AgentMemoryEntity entity = findByMemoryId(memoryId);
        if (entity != null) {
            insertWithEmbedding(entity, embedding);
        }
    }
    
    @Override
    public void deleteEmbedding(String memoryId) {
        try {
            DeleteParam deleteParam = DeleteParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withExpr(FIELD_ID + " == \"" + memoryId + "\"")
                .build();
            
            R<MutationResult> deleteResult = milvusServiceClient.delete(deleteParam);
            if (deleteResult.getStatus() == R.Status.Success.getCode()) {
                log.debug("Agent Memory 向量删除成功: {}", memoryId);
            }
        } catch (Exception e) {
            log.warn("Agent Memory 向量删除异常: {}", memoryId, e);
        }
    }
    
    /**
     * 计算余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dotProduct = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
    
    /**
     * 实体转持久化对象
     */
    private AgentMemoryPO convertToPO(AgentMemoryEntity entity) {
        AgentMemoryPO po = new AgentMemoryPO();
        po.setMemoryId(entity.getMemoryId());
        po.setTenantId(entity.getTenantId());
        po.setUserId(entity.getUserId());
        po.setAgentId(entity.getAgentId());
        po.setSessionId(entity.getSessionId());
        po.setContent(entity.getContent());
        po.setContentHash(entity.getContentHash());
        po.setMemoryType(entity.getMemoryType().name());
        po.setScope(entity.getScope());
        po.setImportance(entity.getImportance());
        po.setSource(entity.getSource());
        po.setMetadata(entity.getMetadata());
        po.setStatus(entity.getStatus());
        if (entity.getExpiresAt() != null) {
            po.setExpiresAt(LocalDateTime.ofInstant(entity.getExpiresAt(), ZoneId.systemDefault()));
        }
        if (entity.getCreatedAt() != null) {
            po.setCreatedAt(LocalDateTime.ofInstant(entity.getCreatedAt(), ZoneId.systemDefault()));
        }
        if (entity.getUpdatedAt() != null) {
            po.setUpdatedAt(LocalDateTime.ofInstant(entity.getUpdatedAt(), ZoneId.systemDefault()));
        }
        return po;
    }
    
    /**
     * 持久化对象转实体
     */
    private AgentMemoryEntity convertToEntity(AgentMemoryPO po) {
        AgentMemoryEntity entity = AgentMemoryEntity.builder()
            .id(po.getId())
            .memoryId(po.getMemoryId())
            .tenantId(po.getTenantId())
            .userId(po.getUserId())
            .agentId(po.getAgentId())
            .sessionId(po.getSessionId())
            .content(po.getContent())
            .contentHash(po.getContentHash())
            .memoryType(MemoryType.valueOf(po.getMemoryType()))
            .scope(po.getScope())
            .importance(po.getImportance())
            .source(po.getSource())
            .metadata(po.getMetadata())
            .status(po.getStatus())
            .build();
        
        if (po.getExpiresAt() != null) {
            entity.setExpiresAt(po.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant());
        }
        if (po.getCreatedAt() != null) {
            entity.setCreatedAt(po.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
        }
        if (po.getUpdatedAt() != null) {
            entity.setUpdatedAt(po.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant());
        }
        
        return entity;
    }
}
