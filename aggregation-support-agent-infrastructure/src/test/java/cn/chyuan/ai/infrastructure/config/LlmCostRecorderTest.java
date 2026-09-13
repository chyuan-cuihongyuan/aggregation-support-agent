package cn.chyuan.ai.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * b-13 LLM 成本记录器契约：token 计数、单价表换算、未配模型零成本、
 * registry 缺席 NOOP、空 usage 跳过。
 */
class LlmCostRecorderTest {

    private Usage usage(long prompt, long completion) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenAnswer(inv -> (int) prompt);
        when(usage.getCompletionTokens()).thenAnswer(inv -> (int) completion);
        return usage;
    }

    private ModelPricingProperties pricingFor(String model, double in, double out) {
        ModelPricingProperties props = new ModelPricingProperties();
        ModelPricingProperties.Pricing p = new ModelPricingProperties.Pricing();
        p.setInputPer1kUsd(in);
        p.setOutputPer1kUsd(out);
        props.getPricing().put(model, p);
        return props;
    }

    @Test
    void tokensAndCostCountedWithPricingTable() {
        ModelPricingProperties props = pricingFor("glm-5", 0.5, 2.0);
        LlmCostRecorder recorder = LlmCostRecorder.withSimpleRegistry(props);
        MeterRegistry registry = recorder.registryForTest();

        recorder.record("glm-5", usage(1000, 500));

        assertThat(registry.get("llm.tokens").tag("model", "glm-5")
                .tag("direction", "prompt").counter().count()).isEqualTo(1000.0);
        assertThat(registry.get("llm.tokens").tag("model", "glm-5")
                .tag("direction", "completion").counter().count()).isEqualTo(500.0);
        // 1000/1000*0.5 + 500/1000*2.0 = 1.5
        assertThat(registry.get("llm.cost.usd").tag("model", "glm-5").counter().count())
                .isEqualTo(1.5);
    }

    @Test
    void unpricedModelCountsTokensButZeroCost() {
        LlmCostRecorder recorder = LlmCostRecorder.withSimpleRegistry(new ModelPricingProperties());
        MeterRegistry registry = recorder.registryForTest();

        recorder.record("unknown-model", usage(10, 20));

        assertThat(registry.get("llm.tokens").tag("model", "unknown-model")
                .tag("direction", "prompt").counter().count()).isEqualTo(10.0);
        assertThat(recorder.costUsd("unknown-model", 10, 20)).isZero();
    }

    @Test
    void zeroZeroUsageIsSkipped() {
        ModelPricingProperties props = pricingFor("glm-5", 1.0, 1.0);
        LlmCostRecorder recorder = LlmCostRecorder.withSimpleRegistry(props);
        MeterRegistry registry = recorder.registryForTest();

        recorder.record("glm-5", usage(0, 0));

        assertThat(registry.find("llm.tokens").counters()).isEmpty();
    }

    @Test
    void costUsdComputesFromTable() {
        ModelPricingProperties props = pricingFor("m", 0.25, 1.0);
        LlmCostRecorder recorder = LlmCostRecorder.withSimpleRegistry(props);

        assertThat(recorder.costUsd("m", 2000, 1500)).isEqualTo(2.0);
    }
}
