package cn.chyuan.ai.domain.rag.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;

import java.util.List;

/**
 * 向量存储仓库接口 — 管理文档向量的持久化存储和相似性检索
 * <p>
 * 实现类可对接 Milvus、Pinecone、Qdrant 等向量数据库
 */
public interface IVectorStoreRepository {

    /**
     * 确保集合存在 — 应用启动时调用，自动创建所需的向量集合和索引
     */
    void ensureCollection();

    /**
     * 插入文档块向量 — 将分块后的文档及其向量批量写入存储
     *
     * @param chunks 文档分块实体列表（包含内容、向量、元数据）
     */
    void insertChunks(List<DocumentChunkEntity> chunks);

    /**
     * 向量相似性检索 — 根据查询向量搜索最相似的文档
     *
     * @param queryVector 查询文本的向量表示
     * @param topK        返回最相似的 K 个结果
     * @return 检索结果列表，按相似度降序排列
     */
    List<VectorSearchResultVO> search(float[] queryVector, int topK);

    List<VectorSearchResultVO> search(float[] queryVector, int topK, TenantScopeVO scope);

    void deleteByDocumentId(String documentId, TenantScopeVO scope);

    /**
     * 健康检查 — 检查向量数据库连接是否正常
     *
     * @return true 表示连接正常
     */
    boolean healthCheck();

}
