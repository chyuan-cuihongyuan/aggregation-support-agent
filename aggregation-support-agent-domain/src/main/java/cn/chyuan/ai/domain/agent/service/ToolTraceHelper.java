package cn.chyuan.ai.domain.agent.service;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ToolTraceHelper {

    private final io.opentelemetry.api.trace.Tracer tracer =
        GlobalOpenTelemetry.getTracer("agent-chyuan", "1.0.0");

    public Span startParamBuildSpan(String toolName) {
        return tracer.spanBuilder("tool.param_build")
            .setAttribute("tool.name", toolName)
            .startSpan();
    }

    public Span startExecuteSpan(String toolName) {
        return tracer.spanBuilder("tool.execute")
            .setAttribute("tool.name", toolName)
            .startSpan();
    }

    public Span startResultParseSpan(String toolName) {
        return tracer.spanBuilder("tool.result_parse")
            .setAttribute("tool.name", toolName)
            .startSpan();
    }
}
