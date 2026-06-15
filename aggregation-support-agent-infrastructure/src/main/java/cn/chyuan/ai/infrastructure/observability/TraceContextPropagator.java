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

    private static final TextMapSetter<Map<String, String>> MAP_SETTER = Map::put;

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    public void inject(Context context, Map<String, String> carrier) {
        propagator.inject(context, carrier, MAP_SETTER);
    }

    public Context extract(Context context, Map<String, String> carrier) {
        return propagator.extract(context, carrier, MAP_GETTER);
    }
}
