package cn.chyuan.ai.infrastructure.mq;

import cn.chyuan.ai.infrastructure.observability.TraceContextPropagator;
import io.opentelemetry.context.Context;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class TraceAwareRocketMQProducer {

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private TraceContextPropagator propagator;

    public void send(String destination, Object payload) {
        Map<String, String> headers = new HashMap<>();
        propagator.inject(Context.current(), headers);

        Message<?> message = MessageBuilder.withPayload(payload)
            .setHeader("traceparent", headers.get("traceparent"))
            .build();

        rocketMQTemplate.convertAndSend(destination, message);
    }
}
