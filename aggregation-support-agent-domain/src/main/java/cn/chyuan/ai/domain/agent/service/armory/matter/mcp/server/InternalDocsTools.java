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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
    @Value("${rag.top-k}")
    private int topK;

    /** RAG 检索线程池，复用 EnhancedRagService 的同一线程池执行批量并行检索 */
    @Autowired
    @Qualifier("ragRetrievalExecutor")
    private AsyncTaskExecutor ragRetrievalExecutor;

    /** 批量并行检索的单查询超时（毫秒），超时的查询返回错误占位，不拖垮整批 */
    @Value("${rag.batch.per-query-timeout-ms:15000}")
    private long batchPerQueryTimeoutMs;

    /** 批量查询的最大条数，超过部分截断丢弃并记日志，防止恶意/异常的超大批次打满线程池 */
    @Value("${rag.batch.max-queries:10}")
    private int batchMaxQueries;

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
    @Tool(description = "查询内部知识库文档，通过语义检索获取与查询最相关的资料，包括但不限于：产品信息与价格、运维文档、故障案例、架构说明、技术规范、业务数据等。当用户询问任何可能存在于知识库中的具体信息时，都应使用此工具。若一次需要查询多个不同主题，请改用 queryInternalDocsBatch 一次性批量查询，避免逐条串行检索拖慢响应")
    public String queryInternalDocs(String query) {
        log.info("工具调用: 查询内部文档, query={}, topK={}", query, topK);

        if (ragService == null) {
            return toJson(ragDisabledResponse(query));
        }

        // 强校验作用域：禁止无作用域检索导致跨租户数据泄露
        TenantScopeVO scope = resolveScope();
        if (scope == null || !scope.isValid()) {
            logScopeMissing(query);
            return toJson(scopeMissingResponse(query));
        }

        return toJson(doSearch(query, scope));
    }

    /**
     * 批量查询内部知识库文档 — 一次工具调用并行检索多个查询，消除逐条串行检索的延迟
     * <p>
     * 与 {@link #queryInternalDocs(String)} 等价，但接收多个查询并在 {@code ragRetrievalExecutor}
     * 线程池上并行执行。租户作用域与证据收集器 Holder 在调用线程上捕获一次，
     * 通过 {@link RagSourceCollector#attach}/{@link RagSourceCollector#detach} 显式注入到每个 worker 线程，
     * 解决池化线程不继承 {@code InheritableThreadLocal} 导致的作用域丢失与证据静默丢弃问题。
     * <p>
     * 单个查询失败或超时不影响其余查询，对应结果项以 error 占位返回。
     *
     * @param queries 自然语言查询列表，例如：["i5-14700KF 进货价", "RTX 4070 Ti 进货价"]
     * @return JSON 格式的批量检索结果，按入参顺序返回每个查询的命中文档
     */
    @Tool(description = "批量查询内部知识库文档 — 一次性并行检索多个查询主题。当用户的问题涉及多个独立条目（如同时询问多个商品的价格、多个故障案例、多份文档）时，必须使用此工具一次性传入全部查询，而不是多次调用 queryInternalDocs，以并行检索大幅缩短响应时间")
    public String queryInternalDocsBatch(List<String> queries) {
        log.info("工具调用: 批量查询内部文档, queryCount={}, topK={}", queries == null ? 0 : queries.size(), topK);

        if (ragService == null) {
            return toJson(ragDisabledResponse(null));
        }
        if (queries == null || queries.isEmpty()) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("resultCount", 0);
            resp.put("results", Collections.emptyList());
            return toJson(resp);
        }

        // 截断超大批次，避免一次打满线程池
        List<String> effectiveQueries = queries;
        if (queries.size() > batchMaxQueries) {
            log.warn("批量查询条数 {} 超过上限 {}，截断丢弃多余查询", queries.size(), batchMaxQueries);
            effectiveQueries = queries.subList(0, batchMaxQueries);
        }

        // 作用域只在调用线程校验一次，worker 线程不再各自 resolve
        TenantScopeVO scope = resolveScope();
        if (scope == null || !scope.isValid()) {
            logScopeMissing(String.join(" | ", effectiveQueries));
            // 整批拒绝，每个查询返回作用域缺失占位
            List<Map<String, Object>> items = effectiveQueries.stream()
                    .map(this::scopeMissingResponse)
                    .collect(Collectors.toList());
            return toJson(buildBatchResponse(items));
        }

        // 捕获调用线程的证据 Holder，注入到并行 worker，保证 RagSourceCollector.append 命中同一 Holder
        final RagSourceCollector.Holder holder = RagSourceCollector.currentHolder();
        final TenantScopeVO capturedScope = scope;

        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>(effectiveQueries.size());
        for (String query : effectiveQueries) {
            CompletableFuture<Map<String, Object>> future = CompletableFuture
                    .supplyAsync(() -> doSearchWithContext(query, capturedScope, holder), ragRetrievalExecutor)
                    .orTimeout(batchPerQueryTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        // 超时/异常属于降级（返回占位，不阻断整批），用 WARN 而非 ERROR；打印异常类名避免 TimeoutException 的 null message
                        log.warn("批量子查询超时或失败(已降级返回占位): query={}, 异常={}, rootMessage={}",
                                query, ex.getClass().getSimpleName(), rootMessage(ex));
                        return errorResponse(query, "文档检索失败: " + rootMessage(ex));
                    });
            futures.add(future);
        }

        List<Map<String, Object>> items = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        log.info("批量内部文档检索完成, 查询数={}", items.size());
        return toJson(buildBatchResponse(items));
    }

    /**
     * 在 worker 线程上执行单查询检索，负责跨线程上下文的注入与清理。
     * <p>
     * 池化线程复用时不会继承 {@code InheritableThreadLocal}，必须显式 attach 租户作用域与证据 Holder，
     * 否则 {@code searchWithTrace} 内部的作用域兜底与 {@code RagSourceCollector.append} 会拿到 null。
     */
    private Map<String, Object> doSearchWithContext(String query, TenantScopeVO scope, RagSourceCollector.Holder holder) {
        RagSourceCollector.attach(holder);
        RequestScopeContext.attach(scope);
        try {
            return doSearch(query, scope);
        } finally {
            RequestScopeContext.clear();
            RagSourceCollector.detach();
        }
    }

    /**
     * 核心检索逻辑 — 执行一次带证据链的语义检索并构建结果对象。
     * <p>
     * 作用域已由调用方校验并传入；证据通过 {@link RagSourceCollector#append} 归集到当前线程的 Holder。
     * 异常被捕获并转为 error 结果项，保证批量场景下单查询失败不影响整批。
     */
    private Map<String, Object> doSearch(String query, TenantScopeVO scope) {
        try {
            SearchOutcomeVO outcome = ragService.searchWithTrace(query, topK, scope);

            // 把命中证据归集到收集器，ChatService 在出口统一 drain 后返回前端
            if (outcome != null && outcome.getSources() != null) {
                RagSourceCollector.append(outcome.getSources());
            }

            List<VectorSearchResultVO> results = outcome != null && outcome.getRawResults() != null
                    ? outcome.getRawResults() : Collections.emptyList();

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

            log.info("内部文档检索完成, query={}, 匹配到 {} 条结果", query, results.size());
            return response;
        } catch (Exception e) {
            log.error("内部文档检索失败: query={}, 错误: {}", query, e.getMessage(), e);
            return errorResponse(query, "文档检索失败: " + e.getMessage());
        }
    }

    /** 解析并兜底租户作用域：优先取 ThreadLocal 快照，不完整时从证据收集器 Holder 兜底 */
    private TenantScopeVO resolveScope() {
        TenantScopeVO scope = RequestScopeContext.snapshot();
        if (scope == null || !scope.isValid()) {
            scope = RagSourceCollector.currentTenantScope();
        }
        return scope;
    }

    private void logScopeMissing(String query) {
        log.warn("RAG检索缺失租户作用域，拒绝执行。诊断信息: query={}, thread={}, threadId={}",
                query,
                Thread.currentThread().getName(),
                Thread.currentThread().getId());
    }

    private Map<String, Object> buildBatchResponse(List<Map<String, Object>> items) {
        Map<String, Object> response = new HashMap<>();
        response.put("resultCount", items.size());
        response.put("results", items);
        return response;
    }

    private Map<String, Object> ragDisabledResponse(String query) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", true);
        errorResponse.put("message", "RAG服务未启用，请配置milvus.enabled=true");
        if (query != null) {
            errorResponse.put("query", query);
        }
        return errorResponse;
    }

    private Map<String, Object> scopeMissingResponse(String query) {
        return errorResponse(query, "知识库检索暂时不可用，请稍后重试或联系管理员");
    }

    private Map<String, Object> errorResponse(String query, String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", true);
        errorResponse.put("message", message);
        errorResponse.put("query", query);
        return errorResponse;
    }

    /** 提取异常根因消息，CompletionException/ExecutionException 会包裹真实异常 */
    private String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    /** 统一 JSON 序列化，序列化失败时返回降级错误串 */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("检索结果序列化失败: {}", e.getMessage());
            return "{\"error\":true,\"message\":\"检索结果序列化异常\"}";
        }
    }

    /**
     * 【Phase 6.1】退出循环工作流工具 — 支持 LoopAgent 条件终止
     *
     * 在 Reflexion/Replan 工作流中，当评估结果满足预期时，Reflector/Replanner 可以调用此工具
     * 提前终止 LoopAgent 的迭代，避免不必要的循环。
     *
     * @param conclusion 评估结论（说明为什么退出循环）
     * @return 退出确认消息
     */
    @Tool(description = """
        退出当前循环工作流（Reflexion/Replan Loop）。

        使用场景：
        1. 在 Reflexion 工作流中，当 Reflector 评估结果为 PASSED 时调用
        2. 在 Replan 工作流中，当 Replanner 决定不再需要重规划时调用
        3. 当达到预期目标，不需要继续迭代时调用

        注意：
        - 调用此工具后，循环工作流将立即终止，不再继续迭代
        - 请确保在评估结果满足预期时才调用此工具
        """)
    public String exitLoop(String conclusion) {
        log.info("Agent 请求退出循环工作流，结论: {}", conclusion);

        // 通过设置特定的标记来通知 LoopAgent 终止
        // ADK 的 LoopAgent 会检查 toolContext 中的 escalate 标志
        // 但由于我们无法直接访问 toolContext，这里返回特殊消息
        // 实际的循环终止需要在 Agent 的 instruction 中引导 LLM 不再继续迭代

        return String.format("【循环终止】%s\n\n工作流已根据评估结论终止迭代。", conclusion);
    }
}
