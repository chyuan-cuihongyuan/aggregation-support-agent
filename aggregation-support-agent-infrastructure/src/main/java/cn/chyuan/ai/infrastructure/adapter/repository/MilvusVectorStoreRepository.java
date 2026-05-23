package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.infrastructure.config.MilvusConfigProperties;
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
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Milvus 向量数据库仓库实现 — 管理 biz 集合的创建、向量插入和相似性检索
 * <p>
 * 实现 IVectorStoreRepository 接口，对接 Milvus 向量数据库。
 * 集合 Schema 包含四个字段：
 * <ul>
 *   <li>id: INT64（自增主键）</li>
 *   <li>vector: FLOAT_VECTOR（1024 维，DashScope text-embedding-v4）</li>
 *   <li>content: VARCHAR（原文内容，最大 65535 字符）</li>
 *   <li>metadata: JSON（元数据，包含来源文件、分块索引等）</li>
 * </ul>
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true", matchIfMissing = false)
public class MilvusVectorStoreRepository implements IVectorStoreRepository {

    /** 字段名称常量 */
    private static final String FIELD_ID = "id";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_METADATA = "metadata";

    /** 搜索参数 — IVF_FLAT 索引的 nprobe 值 */
    private static final String SEARCH_PARAMS = "{\"nprobe\": 128}";

    @Resource
    private MilvusServiceClient milvusServiceClient;

    @Resource
    private MilvusConfigProperties milvusConfigProperties;

    /**
     * 确保集合存在 — 应用启动时自动调用，创建所需的向量集合和索引
     * <p>
     * 执行流程：
     * 1. 检查集合是否已存在
     * 2. 不存在则创建集合（含 Schema 定义）
     * 3. 在 vector 字段上创建 IVF_FLAT 索引
     * 4. 将集合加载到内存以支持检索
     */
    @Override
    public void ensureCollection() {
        String collectionName = milvusConfigProperties.getCollectionName();
        try {
            // 1. 检查集合是否已存在
            R<Boolean> hasCollection = milvusServiceClient.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());

            if (hasCollection.getData() == Boolean.TRUE) {
                log.info("Milvus 集合已存在，跳过创建: {}", collectionName);
                // 确保集合已加载到内存
                loadCollection(collectionName);
                return;
            }

            // 2. 定义集合 Schema
            FieldType idField = FieldType.newBuilder()
                    .withName(FIELD_ID)
                    .withDataType(DataType.Int64)
                    .withPrimaryKey(true)
                    .withAutoID(true)
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

            FieldType metadataField = FieldType.newBuilder()
                    .withName(FIELD_METADATA)
                    .withDataType(DataType.JSON)
                    .build();

            // 3. 创建集合
            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withDescription("RAG 文档向量存储集合")
                    .withShardsNum(2)
                    .addFieldType(idField)
                    .addFieldType(vectorField)
                    .addFieldType(contentField)
                    .addFieldType(metadataField)
                    .build();

            R<RpcStatus> createResult = milvusServiceClient.createCollection(createParam);
            if (createResult.getStatus() != R.Status.Success.getCode()) {
                log.error("创建 Milvus 集合失败: {}", createResult.getMessage());
                return;
            }
            log.info("Milvus 集合创建成功: {}", collectionName);

            // 4. 在 vector 字段上创建 IVF_FLAT 索引
            CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName(FIELD_VECTOR)
                    .withIndexType(io.milvus.param.IndexType.IVF_FLAT)
                    .withMetricType(io.milvus.param.MetricType.L2)
                    .withExtraParam("{\"nlist\": " + milvusConfigProperties.getNlist() + "}")
                    .build();

            R<RpcStatus> indexResult = milvusServiceClient.createIndex(indexParam);
            if (indexResult.getStatus() != R.Status.Success.getCode()) {
                log.error("创建 Milvus 索引失败: {}", indexResult.getMessage());
                return;
            }
            log.info("Milvus 索引创建成功: IVF_FLAT, nlist={}", milvusConfigProperties.getNlist());

            // 5. 将集合加载到内存
            loadCollection(collectionName);

        } catch (Exception e) {
            log.error("确保 Milvus 集合存在时发生异常: {}", e.getMessage(), e);
            throw new RuntimeException("Milvus 集合初始化失败", e);
        }
    }

    /**
     * 插入文档块向量 — 将分块后的文档及其向量批量写入 Milvus
     * <p>
     * 将每个文档分块的向量、内容和元数据组装为 InsertParam 写入集合，
     * 插入完成后执行 Flush 确保数据持久化。
     *
     * @param chunks 文档分块实体列表（包含内容、向量、元数据）
     */
    @Override
    public void insertChunks(List<DocumentChunkEntity> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            log.warn("插入向量数据为空，跳过");
            return;
        }

        String collectionName = milvusConfigProperties.getCollectionName();
        try {
            // 构建向量字段数据
            List<List<Float>> vectors = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            List<JsonObject> metadataList = new ArrayList<>();
            Gson gson = new Gson();

            for (DocumentChunkEntity chunk : chunks) {
                // 将 float[] 转换为 List<Float>
                List<Float> vectorList = new ArrayList<>(chunk.getVector().length);
                for (float v : chunk.getVector()) {
                    vectorList.add(v);
                }
                vectors.add(vectorList);
                contents.add(chunk.getContent());
                // Milvus JSON 字段要求传入 Gson 的 JsonObject
                metadataList.add(gson.toJsonTree(chunk.getMetadata()).getAsJsonObject());
            }

            // 构建插入参数
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field(FIELD_VECTOR, vectors));
            fields.add(new InsertParam.Field(FIELD_CONTENT, contents));
            fields.add(new InsertParam.Field(FIELD_METADATA, metadataList));

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFields(fields)
                    .build();

            // 执行插入
            R<MutationResult> insertResult = milvusServiceClient.insert(insertParam);
            if (insertResult.getStatus() != R.Status.Success.getCode()) {
                log.error("向量数据插入失败: {}", insertResult.getMessage());
                throw new RuntimeException("向量数据插入失败: " + insertResult.getMessage());
            }

            // 刷新数据确保持久化
            milvusServiceClient.flush(FlushParam.newBuilder()
                    .addCollectionName(collectionName)
                    .build());

            log.info("向量数据插入成功: collection={}, count={}", collectionName, chunks.size());

        } catch (Exception e) {
            log.error("插入向量数据时发生异常: {}", e.getMessage(), e);
            throw new RuntimeException("向量数据插入失败", e);
        }
    }

    /**
     * 向量相似性检索 — 根据查询向量在 Milvus 中搜索最相似的文档
     * <p>
     * 使用 L2 距离度量，IVF_FLAT 索引的 nprobe 参数设为 128，
     * 返回结果按 L2 距离升序排列（距离越小越相似）。
     *
     * @param queryVector 查询文本的向量表示（1024 维）
     * @param topK        返回最相似的 K 个结果
     * @return 检索结果列表，按相似度降序排列
     */
    @Override
    public List<VectorSearchResultVO> search(float[] queryVector, int topK) {
        return search(queryVector, topK, null);
    }

    @Override
    public List<VectorSearchResultVO> search(float[] queryVector, int topK, TenantScopeVO scope) {
        String collectionName = milvusConfigProperties.getCollectionName();
        try {
            // 将 float[] 转换为 List<Float>
            List<Float> queryVectorList = new ArrayList<>(queryVector.length);
            for (float v : queryVector) {
                queryVectorList.add(v);
            }
            List<List<Float>> vectors = Collections.singletonList(queryVectorList);

            // 构建搜索参数
            SearchParam.Builder searchBuilder = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withVectorFieldName(FIELD_VECTOR)
                    .withTopK(topK)
                    .withMetricType(io.milvus.param.MetricType.L2)
                    .withVectors(vectors)
                    .withParams(SEARCH_PARAMS)
                    .addOutField(FIELD_CONTENT)
                    .addOutField(FIELD_METADATA);
            if (scope != null) {
                searchBuilder.withExpr(buildScopeExpr(scope));
            }
            SearchParam searchParam = searchBuilder.build();

            // 执行搜索
            R<SearchResults> searchResult = milvusServiceClient.search(searchParam);
            if (searchResult.getStatus() != R.Status.Success.getCode()) {
                log.error("向量检索失败: {}", searchResult.getMessage());
                return Collections.emptyList();
            }

            // 解析搜索结果
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResult.getData().getResults());
            List<VectorSearchResultVO> results = new ArrayList<>();

            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                SearchResultsWrapper.IDScore score = wrapper.getIDScore(0).get(i);

                // 提取 content 字段
                String content = "";
                Object contentObj = wrapper.getRowRecords(0).get(i).get(FIELD_CONTENT);
                if (contentObj != null) {
                    content = contentObj.toString();
                }

                // 提取 metadata 字段并反序列化为 Map
                Map<String, Object> metadata = new HashMap<>();
                Object metadataObj = wrapper.getRowRecords(0).get(i).get(FIELD_METADATA);
                if (metadataObj != null) {
                    try {
                        metadata = JSON.parseObject(metadataObj.toString(),
                                new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        log.warn("元数据解析失败，使用空 Map: {}", e.getMessage());
                    }
                }

                results.add(VectorSearchResultVO.builder()
                        .content(content)
                        .score((float) score.getScore())
                        .metadata(metadata)
                        .build());
            }

            log.info("向量检索完成: collection={}, topK={}, resultCount={}", collectionName, topK, results.size());
            return results;

        } catch (Exception e) {
            log.error("向量检索时发生异常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public void deleteByDocumentId(String documentId, TenantScopeVO scope) {
        String collectionName = milvusConfigProperties.getCollectionName();
        try {
            String expr = buildDocumentExpr(documentId, scope);
            R<MutationResult> result = milvusServiceClient.delete(DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr(expr)
                    .build());
            if (result.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Milvus删除失败: " + result.getMessage());
            }
            milvusServiceClient.flush(FlushParam.newBuilder()
                    .addCollectionName(collectionName)
                    .build());
            log.info("Milvus文档向量删除成功: documentId={}", documentId);
        } catch (Exception e) {
            log.error("Milvus文档向量删除失败: documentId={}", documentId, e);
            throw new RuntimeException("Milvus文档向量删除失败", e);
        }
    }

    /**
     * 健康检查 — 检查 Milvus 向量数据库连接是否正常
     * <p>
     * 通过查询集合统计信息来验证连接和集合可用性
     *
     * @return true 表示连接正常且集合可用
     */
    @Override
    public boolean healthCheck() {
        String collectionName = milvusConfigProperties.getCollectionName();
        try {
            R<Boolean> hasCollection = milvusServiceClient.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());

            boolean healthy = hasCollection.getStatus() == R.Status.Success.getCode()
                    && hasCollection.getData() == Boolean.TRUE;

            if (healthy) {
                log.debug("Milvus 健康检查通过: collection={}", collectionName);
            } else {
                log.warn("Milvus 健康检查异常: collection={}, hasCollection={}", collectionName, hasCollection.getData());
            }

            return healthy;

        } catch (Exception e) {
            log.error("Milvus 健康检查失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 加载集合到内存 — 集合必须加载后才能执行向量检索
     *
     * @param collectionName 集合名称
     */
    private void loadCollection(String collectionName) {
        R<RpcStatus> loadResult = milvusServiceClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());

        if (loadResult.getStatus() != R.Status.Success.getCode()) {
            log.warn("加载 Milvus 集合到内存失败: {}", loadResult.getMessage());
        } else {
            log.info("Milvus 集合已加载到内存: {}", collectionName);
        }
    }

    private String buildScopeExpr(TenantScopeVO scope) {
        return "metadata[\"tenantId\"] == \"" + escapeExprValue(scope.getTenantId()) + "\""
                + " && metadata[\"ownerUserId\"] == \"" + escapeExprValue(scope.getOwnerUserId()) + "\"";
    }

    private String buildDocumentExpr(String documentId, TenantScopeVO scope) {
        String expr = "metadata[\"documentId\"] == \"" + escapeExprValue(documentId) + "\"";
        if (scope != null) {
            expr = expr + " && " + buildScopeExpr(scope);
        }
        return expr;
    }

    private String escapeExprValue(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

}
