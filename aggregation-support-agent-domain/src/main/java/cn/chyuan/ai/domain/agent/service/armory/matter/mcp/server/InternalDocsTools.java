package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.IRagService;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 内部文档检索工具 — 通过 RAG 向量检索从知识库中查找相关运维文档
 * <p>
 * 在 AIOps 场景中，智能体需要查询内部运维知识库来辅助故障诊断和根因分析：
 * <ul>
 *   <li>查询历史故障案例和处理方案</li>
 *   <li>获取系统架构文档和运维手册</li>
 *   <li>检索变更记录和发布日志</li>
 * </ul>
 * <p>
 * 底层通过 IRagService 执行向量语义检索，从 Milvus 向量数据库中返回最相关的文档片段。
 * <p>
 * 迁移自 Aggregation-Support-Agent-java 项目，作为本地 MCP 工具注册为 Spring Bean。
 */
@Slf4j
@Service
public class InternalDocsTools {

    /** RAG 检索服务，提供语义搜索能力 */
    @Autowired
    private IRagService ragService;

    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 检索返回的最大文档数量，默认取 Top 3 最相关结果 */
    @Value("${rag.top-k:3}")
    private int topK;

    /**
     * 查询内部运维文档 — 通过自然语言查询从知识库中检索相关文档
     * <p>
     * 使用流程：
     * <ol>
     *   <li>将查询文本通过嵌入模型转换为向量</li>
     *   <li>在 Milvus 向量数据库中执行 L2 距离检索</li>
     *   <li>返回最相关的 Top-K 个文档片段</li>
     * </ol>
     *
     * @param query 自然语言查询文本，例如："支付服务 CPU 飙高的排查方案"
     * @return JSON 格式的检索结果，包含匹配文档内容、相似度分数和元数据
     */
    @Tool(description = "查询内部运维文档知识库，通过语义检索获取与查询最相关的运维文档、故障案例、架构说明等资料")
    public String queryInternalDocs(String query) {
        log.info("工具调用: 查询内部文档, query={}, topK={}", query, topK);

        if (ragService == null) {
            log.warn("RAG服务未启用，无法查询内部文档");
            try {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", true);
                errorResponse.put("message", "RAG服务未启用，请配置milvus.enabled=true");
                errorResponse.put("query", query);
                return objectMapper.writeValueAsString(errorResponse);
            } catch (Exception jsonException) {
                return "{\"error\":true,\"message\":\"RAG服务未启用\"}";
            }
        }

        try {
            // 调用 RAG 服务执行语义检索
            List<VectorSearchResultVO> results = ragService.search(query, topK, RequestScopeContext.get());

            // 构建返回结果
            Map<String, Object> response = new HashMap<>();
            response.put("query", query);
            response.put("resultCount", results.size());
            response.put("results", results.stream().map(result -> {
                Map<String, Object> item = new HashMap<>();
                item.put("content", result.getContent());
                item.put("score", result.getScore());
                item.put("metadata", result.getMetadata());
                return item;
            }).collect(Collectors.toList()));

            String jsonResult = objectMapper.writeValueAsString(response);
            log.info("内部文档检索完成, 匹配到 {} 条结果", results.size());
            return jsonResult;

        } catch (Exception e) {
            log.error("内部文档检索失败: query={}, 错误: {}", query, e.getMessage(), e);
            try {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", true);
                errorResponse.put("message", "文档检索失败: " + e.getMessage());
                errorResponse.put("query", query);
                return objectMapper.writeValueAsString(errorResponse);
            } catch (Exception jsonException) {
                return "{\"error\":true,\"message\":\"文档检索失败且结果序列化异常\"}";
            }
        }
    }
}
