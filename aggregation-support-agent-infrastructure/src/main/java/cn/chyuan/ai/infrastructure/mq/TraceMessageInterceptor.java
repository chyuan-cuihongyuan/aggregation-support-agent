package cn.chyuan.ai.infrastructure.mq;

import cn.chyuan.ai.infrastructure.observability.TraceContext;
import io.opentelemetry.context.Context;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class TraceMessageInterceptor {

    private final TraceContext traceContext;

    public TraceMessageInterceptor(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    /**
     * Inject trace context into message properties before sending
     */
    public void injectTraceContext(Map<String, String> messageProperties) {
        traceContext.injectIntoHeaders(Context.current(), messageProperties);
    }

    /**
     * Extract trace context from message properties when consuming
     */
    public Context extractTraceContext(MessageExt message) {
        Map<String, String> properties = new HashMap<>();
        message.getProperties().forEach(properties::put);
        return traceContext.extractFromHeaders(properties);
    }
}
