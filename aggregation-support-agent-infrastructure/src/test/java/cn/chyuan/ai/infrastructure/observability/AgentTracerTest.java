package cn.chyuan.ai.infrastructure.observability;

import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTracerTest {

    private AgentTracer agentTracer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        agentTracer = new AgentTracer();
    }

    @Test
    void shouldCreateSpanWithAttributes() {
        Span span = agentTracer.createSpan("test-span", Map.of("key", "value"));

        assertThat(span).isNotNull();
        assertThat(span.getSpanContext().isValid()).isTrue();
        span.end();
    }

    @Test
    void shouldExecuteWorkWithinSpan() {
        String result = agentTracer.withSpan("test-span", Map.of(), () -> {
            return "test-result";
        });

        assertThat(result).isEqualTo("test-result");
    }

    @Test
    void shouldHandleExceptionInSpan() {
        assertThatThrownBy(() -> {
            agentTracer.withSpan("test-span", Map.of(), () -> {
                throw new RuntimeException("test-error");
            });
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldStartAgentSpan() {
        Span span = agentTracer.startAgentSpan("agent-123", "TestAgent", "session-456");

        assertThat(span).isNotNull();
        assertThat(span.getSpanContext().isValid()).isTrue();
        span.end();
    }

    @Test
    void shouldStartRAGSpan() {
        Span span = agentTracer.startRAGSpan("test query");

        assertThat(span).isNotNull();
        span.end();
    }

    @Test
    void shouldStartLLMSpan() {
        Span span = agentTracer.startLLMSpan("gpt-4");

        assertThat(span).isNotNull();
        span.end();
    }

    @Test
    void shouldStartToolSpan() {
        Span span = agentTracer.startToolSpan("search-tool", "retrieval");

        assertThat(span).isNotNull();
        span.end();
    }
}
