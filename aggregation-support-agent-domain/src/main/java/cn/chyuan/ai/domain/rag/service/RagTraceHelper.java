package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.infrastructure.observability.AgentTracer;
import io.opentelemetry.api.trace.Span;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RagTraceHelper {

    @Autowired
    private AgentTracer agentTracer;

    public Span startQueryRewriteSpan(String query) {
        return agentTracer.createSpan("rag.query_rewrite", Map.of("rag.query", query));
    }

    public Span startVectorSearchSpan(String query, int retrievalCount) {
        return agentTracer.createSpan("rag.vector_search", Map.of(
            "rag.query", query,
            "rag.retrieval_count", String.valueOf(retrievalCount)
        ));
    }

    public Span startBm25SearchSpan(String query) {
        return agentTracer.createSpan("rag.bm25_search", Map.of("rag.query", query));
    }

    public Span startRerankSpan(String query) {
        return agentTracer.createSpan("rag.rerank", Map.of("rag.query", query));
    }
}
