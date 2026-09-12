package cn.chyuan.ai.domain.agent.service.armory.support;

import io.micrometer.observation.ObservationRegistry;

/**
 * spring-ai GenAI 指标装配回退（SELFLOOP2 loop-212）：
 * 注入的 ObservationRegistry 优先，缺席时回退 NOOP——
 * 保证无 actuator registry 的环境（纯单测/裁剪部署）照常构建 ChatModel。
 */
public final class ObservationWiring {

    private ObservationWiring() {
    }

    public static ObservationRegistry effective(ObservationRegistry injected) {
        return injected != null ? injected : ObservationRegistry.NOOP;
    }
}
