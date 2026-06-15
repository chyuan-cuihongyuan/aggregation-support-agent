package cn.chyuan.ai.infrastructure.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

@Component
public class AgentTracer {

    private final io.opentelemetry.api.trace.Tracer tracer =
        io.opentelemetry.api.GlobalOpenTelemetry.getTracer("agent-chyuan", "1.0.0");

    /**
     * Create a span with attributes
     */
    public Span createSpan(String name, Map<String, String> attributes) {
        Span span = tracer.spanBuilder(name).startSpan();
        attributes.forEach(span::setAttribute);
        return span;
    }

    /**
     * Execute work within a span context
     */
    public <T> T withSpan(String name, Map<String, String> attributes, SpanWork<T> work) {
        Span span = createSpan(name, attributes);
        try (Scope scope = span.makeCurrent()) {
            T result = work.execute();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    /**
     * Execute void work within a span context
     */
    public void withSpan(String name, Map<String, String> attributes, Runnable work) {
        Span span = createSpan(name, attributes);
        try (Scope scope = span.makeCurrent()) {
            work.run();
            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Create a span for agent decision making
     */
    public Span startAgentSpan(String agentId, String agentName, String sessionId) {
        Span span = tracer.spanBuilder("agent.decision")
            .setAttribute(SpanAttributes.AGENT_ID, agentId)
            .setAttribute(SpanAttributes.AGENT_NAME, agentName)
            .setAttribute(SpanAttributes.SESSION_ID, sessionId)
            .startSpan();
        return span;
    }

    /**
     * Create a span for RAG retrieval
     */
    public Span startRAGSpan(String query) {
        Span span = tracer.spanBuilder("rag.retrieval")
            .setAttribute(SpanAttributes.RAG_QUERY, query)
            .startSpan();
        return span;
    }

    /**
     * Create a span for LLM call
     */
    public Span startLLMSpan(String model) {
        Span span = tracer.spanBuilder("llm.call")
            .setAttribute(SpanAttributes.LLM_MODEL, model)
            .startSpan();
        return span;
    }

    /**
     * Create a span for tool execution
     */
    public Span startToolSpan(String toolName, String toolType) {
        Span span = tracer.spanBuilder("tool.execute")
            .setAttribute(SpanAttributes.TOOL_NAME, toolName)
            .setAttribute(SpanAttributes.TOOL_TYPE, toolType)
            .startSpan();
        return span;
    }

    @FunctionalInterface
    public interface SpanWork<T> {
        T execute() throws Exception;
    }
}
