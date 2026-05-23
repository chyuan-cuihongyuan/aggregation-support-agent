package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.model.valobj.DocumentUploadCommand;
import cn.chyuan.ai.domain.rag.model.valobj.SearchResultDetailVO;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;

import java.util.List;

/**
 * RAG 智能问答服务接口 — 提供文档上传向量化存储和语义检索能力
 * <p>
 * RAG（Retrieval-Augmented Generation）流程：
 * <ol>
 *   <li>文档上传：解析文本 → 分块(800字符,100重叠) → 嵌入 → Milvus存储</li>
 *   <li>语义检索：用户提问 → 嵌入 → L2向量检索 → 返回最相似的文档片段</li>
 * </ol>
 */
public interface IRagService {

    /**
     * 上传文档并自动向量化存储
     * <p>
     * 流程：文本解析 → 按最大分块大小切割(800字符)并保留重叠(100字符) →
     *       批量调用嵌入模型生成向量 → 写入 Milvus 向量数据库
     *
     * @param command 文档上传命令（文件名、内容、类型）
     */
    void uploadDocument(DocumentUploadCommand command);

    void deleteDocument(String documentId, TenantScopeVO scope);

    /**
     * 语义检索 — 根据自然语言查询搜索最相关的文档片段
     * <p>
     * 流程：查询文本 → 嵌入为向量 → Milvus L2 距离检索 Top-K 结果
     *
     * @param query 用户查询文本
     * @param topK  返回最相似的 K 个结果
     * @return 检索结果列表，按相似度降序排列
     */
    List<VectorSearchResultVO> search(String query, int topK);

    List<VectorSearchResultVO> search(String query, int topK, TenantScopeVO scope);

    /**
     * Milvus 健康检查
     *
     * @return true 表示 Milvus 连接正常
     */
    boolean healthCheck();

    /**
     * 检索测试 — 分别返回向量检索、BM25 检索和混合检索的结果
     *
     * @param query 查询文本
     * @param topK  返回结果数量
     * @return 分组检索结果详情
     */
    SearchResultDetailVO searchWithDetails(String query, int topK);

    SearchResultDetailVO searchWithDetails(String query, int topK, TenantScopeVO scope);

}
