package cn.chyuan.ai.infrastructure.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class TraceContext {

    /**
     * Extract trace context from HTTP headers
     */
    public Context extractFromHeaders(Map<String, String> headers) {
        return io.opentelemetry.api.GlobalOpenTelemetry.getPropagators()
            .getTextMapPropagator()
            .extract(Context.current(), headers, new TextMapGetter<Map<String, String>>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    return carrier.get(key);
                }
            });
    }

    /**
     * Inject trace context into HTTP headers
     */
    public void injectIntoHeaders(Context context, Map<String, String> headers) {
        io.opentelemetry.api.GlobalOpenTelemetry.getPropagators()
            .getTextMapPropagator()
            .inject(context, headers, new TextMapSetter<Map<String, String>>() {
                @Override
                public void set(Map<String, String> carrier, String key, String value) {
                    carrier.put(key, value);
                }
            });
    }

    /**
     * Get current span context information
     */
    public Map<String, String> getCurrentTraceInfo() {
        Map<String, String> traceInfo = new HashMap<>();
        Span currentSpan = Span.current();
        if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
            traceInfo.put("traceId", currentSpan.getSpanContext().getTraceId());
            traceInfo.put("spanId", currentSpan.getSpanContext().getSpanId());
            traceInfo.put("sampled", String.valueOf(currentSpan.getSpanContext().isSampled()));
        }
        return traceInfo;
    }
}
