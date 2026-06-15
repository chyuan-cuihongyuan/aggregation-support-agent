package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.infrastructure.observability.AgentTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;

@Component
public class TraceInterceptor implements HandlerInterceptor {

    @Autowired
    private AgentTracer agentTracer;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Span span = agentTracer.createSpan("http.request", Map.of(
            "http.method", request.getMethod(),
            "http.url", request.getRequestURL().toString()
        ));
        span.makeCurrent();
        request.setAttribute("trace.span", span);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Span span = (Span) request.getAttribute("trace.span");
        if (span != null) {
            span.setAttribute("http.status_code", response.getStatus());
            if (ex != null) {
                span.recordException(ex);
            }
            span.end();
        }
    }
}
