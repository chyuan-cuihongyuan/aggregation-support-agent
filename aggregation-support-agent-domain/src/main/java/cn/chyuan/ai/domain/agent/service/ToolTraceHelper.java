package cn.chyuan.ai.domain.agent.service;

import cn.chyuan.ai.infrastructure.observability.AgentTracer;
import io.opentelemetry.api.trace.Span;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ToolTraceHelper {

    @Autowired
    private AgentTracer agentTracer;

    public Span startParamBuildSpan(String toolName) {
        return agentTracer.createSpan("tool.param_build", Map.of("tool.name", toolName));
    }

    public Span startExecuteSpan(String toolName) {
        return agentTracer.createSpan("tool.execute", Map.of("tool.name", toolName));
    }

    public Span startResultParseSpan(String toolName) {
        return agentTracer.createSpan("tool.result_parse", Map.of("tool.name", toolName));
    }
}
