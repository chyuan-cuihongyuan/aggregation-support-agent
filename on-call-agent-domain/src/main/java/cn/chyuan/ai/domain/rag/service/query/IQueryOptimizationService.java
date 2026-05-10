package cn.chyuan.ai.domain.rag.service.query;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询优化服务接口 — 对用户query做加工，提高检索命中率
 */
public interface IQueryOptimizationService {

    /**
     * Query改写 — 将口语化query转化为正式书面表达
     *
     * @param originalQuery 原始查询
     * @param chatHistory   对话历史
     * @return 改写后的查询
     */
    String rewriteQuery(String originalQuery, List<String> chatHistory);

    /**
     * Multi-Query扩展 — 将一个问题扩展为多个不同角度的问法
     *
     * @param originalQuery 原始查询
     * @param count         扩展数量
     * @return 扩展后的查询列表（包含原始查询）
     */
    List<String> expandQuery(String originalQuery, int count);

    /**
     * HyDE（假设文档嵌入）— 生成假设答案用于检索
     *
     * @param query 用户查询
     * @return 假设的答案文档
     */
    String generateHypotheticalDocument(String query);

    /**
     * Step-back Prompting — 将具体问题抽象为背景问题
     *
     * @param specificQuery 具体问题
     * @return 更通用的背景问题
     */
    String generateStepBackQuery(String specificQuery);

}
