package cn.chyuan.ai.domain.agent.service;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LlmTraceHelper {

    private final io.opentelemetry.api.trace.Tracer tracer =
        GlobalOpenTelemetry.getTracer("agent-chyuan", "1.0.0");

    public Span startPromptBuildSpan(String model) {
        return tracer.spanBuilder("llm.prompt_build")
            .setAttribute("llm.model", model)
            .startSpan();
    }

    public Span startApiCallSpan(String model) {
        return tracer.spanBuilder("llm.api_call")
            .setAttribute("llm.model", model)
            .startSpan();
    }

    public Span startResponseParseSpan(String model) {
        return tracer.spanBuilder("llm.response_parse")
            .setAttribute("llm.model", model)
            .startSpan();
    }
}
