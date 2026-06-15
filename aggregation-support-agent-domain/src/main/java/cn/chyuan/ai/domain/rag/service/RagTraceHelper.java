package cn.chyuan.ai.domain.rag.service;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RagTraceHelper {

    private final io.opentelemetry.api.trace.Tracer tracer =
        GlobalOpenTelemetry.getTracer("agent-chyuan", "1.0.0");

    public Span startQueryRewriteSpan(String query) {
        return tracer.spanBuilder("rag.query_rewrite")
            .setAttribute("rag.query", query)
            .startSpan();
    }

    public Span startVectorSearchSpan(String query, int retrievalCount) {
        return tracer.spanBuilder("rag.vector_search")
            .setAttribute("rag.query", query)
            .setAttribute("rag.retrieval_count", String.valueOf(retrievalCount))
            .startSpan();
    }

    public Span startBm25SearchSpan(String query) {
        return tracer.spanBuilder("rag.bm25_search")
            .setAttribute("rag.query", query)
            .startSpan();
    }

    public Span startRerankSpan(String query) {
        return tracer.spanBuilder("rag.rerank")
            .setAttribute("rag.query", query)
            .startSpan();
    }
}
