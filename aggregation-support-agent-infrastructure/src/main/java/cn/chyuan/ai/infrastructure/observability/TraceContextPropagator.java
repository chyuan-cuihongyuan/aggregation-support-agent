package cn.chyuan.ai.infrastructure.observability;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class TraceContextPropagator {

    private final TextMapPropagator propagator = io.opentelemetry.api.GlobalOpenTelemetry.getPropagators().getTextMapPropagator();

    public void inject(Context context, Map<String, String> carrier) {
        propagator.inject(context, carrier, TextMapSetter.mapSetter());
    }

    public Context extract(Context context, Map<String, String> carrier) {
        return propagator.extract(context, carrier, TextMapGetter.mapGetter());
    }
}
