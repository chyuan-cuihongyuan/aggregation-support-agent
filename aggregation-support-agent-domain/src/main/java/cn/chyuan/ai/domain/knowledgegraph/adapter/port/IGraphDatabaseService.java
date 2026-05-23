package cn.chyuan.ai.domain.knowledgegraph.adapter.port;

import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphEntity;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphRelation;
import cn.chyuan.ai.domain.knowledgegraph.model.valobj.SubgraphVO;

import java.util.List;

/**
 * 图数据库服务端口
 */
public interface IGraphDatabaseService {

    /** 初始化 schema（约束和索引） */
    void ensureSchema();

    /** 保存单个实体 */
    String saveEntity(GraphEntity entity);

    /** 批量保存实体 */
    void saveEntities(List<GraphEntity> entities);

    /** 保存单个关系 */
    String saveRelation(GraphRelation relation);

    /** 批量保存关系 */
    void saveRelations(List<GraphRelation> relations);

    /** 按名称搜索实体 */
    List<GraphEntity> findEntitiesByName(String name);

    /** 按类型搜索实体 */
    List<GraphEntity> findEntitiesByType(String type, int limit);

    /** 按嵌入向量搜索实体 */
    List<GraphEntity> findEntitiesByEmbedding(float[] embedding, int topK);

    /** 获取实体子图 */
    SubgraphVO getSubgraph(String entityId, int depth);

    /** 搜索子图 */
    SubgraphVO searchSubgraph(String query, int topK, int depth);

    /** 获取实体总数 */
    long getEntityCount();

    /** 获取关系总数 */
    long getRelationCount();

    /** 健康检查 */
    boolean healthCheck();
}
