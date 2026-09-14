package cn.chyuan.ai.domain.knowledgegraph.graphrag.adapter.port;

/**
 * 全局问答端口（AM5：map 逐批出要点、reduce 汇总答案）。
 * graphrag global search 思想的六边形端口，LLM 适配器实现，异常由编排器降级。
 */
public interface IGlobalInsightPort {

    /** map 阶段：一批社区摘要 + 问题 → 中间要点（每条一行） */
    java.util.List<String> map(String question, java.util.List<String> batchSummaries);

    /** reduce 阶段：全部要点 + 问题 → 最终答案 */
    String reduce(String question, java.util.List<String> insights);
}
