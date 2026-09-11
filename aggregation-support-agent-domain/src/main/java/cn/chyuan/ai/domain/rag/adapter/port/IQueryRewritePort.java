package cn.chyuan.ai.domain.rag.adapter.port;

/**
 * 查询改写端口（工单 0165，W3）— 检索前对用户 query 做改写
 * <p>
 * W 簇端口化裁定：domain 零框架依赖；LLM 端口必带规则兜底实现。
 * <ul>
 *   <li>rule（默认）：{@code RuleBasedQueryRewriter}（内置停用词表 + 可配置同义扩展表）</li>
 *   <li>llm：infrastructure LLM 适配，异常时回退规则版</li>
 * </ul>
 * 开关 rag.rewrite-enabled 默认关（不装配端口，query 原样直通=零回归）；
 * 改写前后 query 都进检索日志（rag_trace.query_text / rewrite_text 双写）。
 */
public interface IQueryRewritePort {

    /**
     * 改写查询
     * <p>
     * 端口契约：实现不得抛出异常（内部兜底返回原 query 或规则改写结果）；
     * 空串 / null 输入原样保真返回。
     *
     * @param query 用户原始查询
     * @return 改写后的查询（无法改写时返回原 query）
     */
    String rewrite(String query);
}
