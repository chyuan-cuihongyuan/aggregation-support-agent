package cn.chyuan.ai.infrastructure.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AgentTracer {

    private final Tracer tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("agent-tracer");

    public Span createSpan(String name, Map<String, String> attributes) {
        Span span = tracer.spanBuilder(name).startSpan();
        attributes.forEach(span::setAttribute);
        return span;
    }

    public <T> T withSpan(String name, Map<String, String> attributes, SpanWork<T> work) {
        Span span = createSpan(name, attributes);
        try (Scope scope = span.makeCurrent()) {
            return work.execute();
        } catch (Exception e) {
            span.recordException(e);
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    @FunctionalInterface
    public interface SpanWork<T> {
        T execute() throws Exception;
    }
}
