package cn.chyuan.ai.controller.interceptor;

import cn.chyuan.ai.infrastructure.observability.AgentTracer;
import cn.chyuan.ai.infrastructure.observability.TraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.HashMap;
import java.util.Map;

@Component
public class TraceInterceptor implements HandlerInterceptor {

    private final AgentTracer agentTracer;
    private final TraceContext traceContext;

    public TraceInterceptor(AgentTracer agentTracer, TraceContext traceContext) {
        this.agentTracer = agentTracer;
        this.traceContext = traceContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Extract trace context from incoming headers
        Map<String, String> headers = new HashMap<>();
        request.getHeaderNames().asIterator().forEachRemaining(name ->
            headers.put(name, request.getHeader(name))
        );

        Context parentContext = traceContext.extractFromHeaders(headers);

        // Create new span for this request
        String spanName = request.getMethod() + " " + request.getRequestURI();
        Span span = agentTracer.createSpan(spanName, Map.of(
            "http.method", request.getMethod(),
            "http.url", request.getRequestURI(),
            "http.user_agent", request.getHeader("User-Agent") != null ? request.getHeader("User-Agent") : "unknown"
        ));

        // Make this span current
        Context newContext = parentContext.with(span);
        Scope scope = newContext.makeCurrent();

        // Store span and scope in request attributes for cleanup
        request.setAttribute("trace.span", span);
        request.setAttribute("trace.scope", scope);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Span span = (Span) request.getAttribute("trace.span");
        Scope scope = (Scope) request.getAttribute("trace.scope");

        if (span != null) {
            if (ex != null) {
                span.setStatus(StatusCode.ERROR, ex.getMessage());
                span.recordException(ex);
            } else {
                span.setStatus(StatusCode.OK);
            }
            span.end();
        }

        if (scope != null) {
            scope.close();
        }
    }
}
