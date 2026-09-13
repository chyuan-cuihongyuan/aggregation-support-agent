package cn.chyuan.ai.infrastructure.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * LLM 成本记录器（b-13 / 工单 1122，借鉴 LiteLLM cost tracking 思想）。
 * <p>
 * 指标（model 维度）：
 * <ul>
 *   <li>{@code llm.tokens}（Counter，direction=prompt|completion）</li>
 *   <li>{@code llm.cost.usd}（DoubleCounter，单价表换算；未配模型记 0）</li>
 * </ul>
 * registry 缺席时全 NOOP（承 loop-212 观测装配回退模式）。
 */
@Slf4j
@Component
public class LlmCostRecorder {

    private final MeterRegistry registry;
    private final ModelPricingProperties pricingProperties;

    @Autowired
    public LlmCostRecorder(@Autowired(required = false) MeterRegistry registry,
                           ModelPricingProperties pricingProperties) {
        this.registry = registry;
        this.pricingProperties = pricingProperties;
    }

    /** 记录一次调用的 token 用量与估算成本；usage 缺席时静默跳过 */
    public void record(String model, Usage usage) {
        if (usage == null || registry == null) {
            return;
        }
        long promptTokens = positiveOrZero(usage.getPromptTokens());
        long completionTokens = positiveOrZero(usage.getCompletionTokens());
        if (promptTokens == 0 && completionTokens == 0) {
            return;
        }
        Counter.builder("llm.tokens")
                .tag("model", model)
                .tag("direction", "prompt")
                .register(registry)
                .increment(promptTokens);
        Counter.builder("llm.tokens")
                .tag("model", model)
                .tag("direction", "completion")
                .register(registry)
                .increment(completionTokens);

        double cost = costUsd(model, promptTokens, completionTokens);
        Counter.builder("llm.cost.usd")
                .tag("model", model)
                .register(registry)
                .increment(cost);
    }

    /** 单价表换算（USD）；未配置模型成本为 0（token 计数不受影响） */
    public double costUsd(String model, long promptTokens, long completionTokens) {
        ModelPricingProperties.Pricing pricing = pricingProperties.getPricing().get(model);
        if (pricing == null) {
            return 0.0;
        }
        return promptTokens / 1000.0 * pricing.getInputPer1kUsd()
                + completionTokens / 1000.0 * pricing.getOutputPer1kUsd();
    }

    /** registry 缺席时的可测试入口：走 SimpleMeterRegistry 记录 */
    static LlmCostRecorder withSimpleRegistry(ModelPricingProperties props) {
        return new LlmCostRecorder(new SimpleMeterRegistry(), props);
    }

    MeterRegistry registryForTest() {
        return registry;
    }

    private static long positiveOrZero(Number n) {
        return n == null ? 0 : Math.max(0, n.longValue());
    }
}
