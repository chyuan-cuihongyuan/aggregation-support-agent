package cn.chyuan.ai.infrastructure.config;

import io.opentelemetry.context.Context;
import org.springframework.core.task.TaskDecorator;

public class TraceableTaskDecorator implements TaskDecorator {
    
    @Override
    public Runnable decorate(Runnable runnable) {
        Context context = Context.current();
        return () -> {
            try (var scope = context.makeCurrent()) {
                runnable.run();
            }
        };
    }
}
