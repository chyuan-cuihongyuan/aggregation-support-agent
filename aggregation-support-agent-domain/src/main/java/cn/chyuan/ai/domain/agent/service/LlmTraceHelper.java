package cn.chyuan.ai.domain.agent.service;

import cn.chyuan.ai.infrastructure.observability.AgentTracer;
import io.opentelemetry.api.trace.Span;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LlmTraceHelper {

    @Autowired
    private AgentTracer agentTracer;

    public Span startPromptBuildSpan(String model) {
        return agentTracer.createSpan("llm.prompt_build", Map.of("llm.model", model));
    }

    public Span startApiCallSpan(String model) {
        return agentTracer.createSpan("llm.api_call", Map.of("llm.model", model));
    }

    public Span startResponseParseSpan(String model) {
        return agentTracer.createSpan("llm.response_parse", Map.of("llm.model", model));
    }
}
