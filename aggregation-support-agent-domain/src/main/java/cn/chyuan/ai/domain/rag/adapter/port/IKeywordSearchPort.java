package cn.chyuan.ai.domain.rag.adapter.port;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;

import java.util.List;

/**
 * 关键词检索端口（工单 0163，W1 混合检索的关键词路）
 * <p>
 * 面向 Elasticsearch match 查询等词面检索能力的 domain 端口：
 * 混合检索开启（rag.hybrid-enabled=true）时，编排层经本端口取得
 * 关键词路 ranked list，与向量路一起送 {@code RrfFusionService} 纯函数融合。
 * <p>
 * 端口契约：
 * <ul>
 *   <li>实现不得抛出异常打断主链路（内部降级返回空列表）</li>
 *   <li>{@link #isAvailable()} 为 false 时编排层跳过关键词路（单路=原行为）</li>
 * </ul>
 */
public interface IKeywordSearchPort {

    /**
     * 关键词检索 — 返回按词面相关性排序的 topK 结果
     *
     * @param query 用户查询文本
     * @param topK  返回数量上限
     * @param scope 租户作用域（可为 null，实现需自行兜底）
     * @return 检索结果列表，失败时返回空列表
     */
    List<VectorSearchResultVO> search(String query, int topK, TenantScopeVO scope);

    /**
     * 关键词检索是否可用（如 ES 连接配置就绪）
     */
    boolean isAvailable();
}
