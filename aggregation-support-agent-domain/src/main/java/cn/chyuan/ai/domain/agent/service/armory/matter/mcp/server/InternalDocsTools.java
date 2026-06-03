package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.rag.model.valobj.SearchOutcomeVO;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.IRagService;
import cn.chyuan.ai.domain.rag.support.RagSourceCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
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
     *   <li>调用 RagService.searchWithTrace，得到带证据链的检索输出</li>
     *   <li>把命中证据 append 到 RagSourceCollector，供 ChatService 出口取回返回前端</li>
     *   <li>把原始结果序列化为 JSON 字符串返回给 LLM（与既有行为兼容）</li>
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
            // 强校验作用域：禁止无作用域检索导致跨租户数据泄露
            TenantScopeVO scope = RequestScopeContext.snapshot();
            if (scope == null) {
                scope = RagSourceCollector.currentTenantScope();
            }
            if (!hasTenantScope(scope)) {
                // 记录详细诊断信息，帮助排查作用域传递链路中的断裂点
                log.warn("RAG检索缺失租户作用域，拒绝执行。诊断信息: query={}, thread={}, threadId={}",
                        query,
                        Thread.currentThread().getName(),
                        Thread.currentThread().getId());
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", true);
                errorResponse.put("message", "知识库检索暂时不可用，请稍后重试或联系管理员");
                errorResponse.put("query", query);
                return objectMapper.writeValueAsString(errorResponse);
            }

            // 调用 RAG 服务执行带证据链的语义检索
            SearchOutcomeVO outcome = ragService.searchWithTrace(query, topK, scope);

            // 把命中证据归集到收集器，ChatService 在出口统一 drain 后返回前端
            if (outcome != null && outcome.getSources() != null) {
                RagSourceCollector.append(outcome.getSources());
            }

            // 仍然基于 rawResults 序列化字符串回包给 LLM，行为与改造前一致
            List<VectorSearchResultVO> results = outcome != null && outcome.getRawResults() != null
                    ? outcome.getRawResults() : Collections.emptyList();

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

    private boolean hasTenantScope(TenantScopeVO scope) {
        return scope != null
                && scope.getTenantId() != null
                && !scope.getTenantId().isBlank()
                && scope.getOwnerUserId() != null
                && !scope.getOwnerUserId().isBlank();
    }
}
