package cn.chyuan.ai.domain.knowledgegraph.graphrag.adapter.repository;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunityPartitionVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;

/**
 * 图索引存储端口（AM9：导出端点取数面；infrastructure 内存实现）。
 */
public interface IGraphIndexRepository {

    /** 保存索引快照与社区划分 */
    void save(String indexId, GraphIndexVO index, CommunityPartitionVO partition);

    /** 取索引快照（不存在返回 null） */
    GraphIndexVO findIndex(String indexId);

    /** 取社区划分（不存在返回 null） */
    CommunityPartitionVO findPartition(String indexId);
}
