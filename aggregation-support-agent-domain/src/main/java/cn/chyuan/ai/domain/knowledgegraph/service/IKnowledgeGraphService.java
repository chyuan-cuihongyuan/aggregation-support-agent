package cn.chyuan.ai.domain.knowledgegraph.service;

import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphEntity;
import cn.chyuan.ai.domain.knowledgegraph.model.valobj.GraphSearchResultVO;
import cn.chyuan.ai.domain.knowledgegraph.model.valobj.SubgraphVO;
import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;

import java.util.List;
import java.util.Map;

/**
 * 知识图谱领域服务接口
 */
public interface IKnowledgeGraphService {

    /** 文档上传后触发图谱构建 */
    void buildGraphFromDocument(String documentId, List<DocumentChunkEntity> chunks);

    /** 实体搜索 */
    List<GraphEntity> searchEntities(String query, int topK);

    /** 图谱检索（实体匹配 + 子图遍历） */
    GraphSearchResultVO graphSearch(String query, int topK, int subgraphDepth);

    /** 获取实体子图 */
    SubgraphVO getEntitySubgraph(String entityId, int depth);

    /** 图谱统计 */
    Map<String, Object> getGraphStatistics();

    /** 健康检查 */
    boolean healthCheck();

    /** 重试失败的图谱构建任务 */
    void retryTask(String documentId);
}
