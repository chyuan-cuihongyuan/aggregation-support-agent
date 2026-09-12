package cn.chyuan.ai.domain.agent.service.armory.support;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * ObservationRegistry 装配回退契约（SELFLOOP2 loop-212）：
 * 注入优先、缺席回退 NOOP——LLM GenAI 指标链路的启用开关行为锁定。
 */
@DisplayName("ObservationWiring 装配回退契约")
class ObservationWiringTest {

    @Test
    @DisplayName("注入存在时返回注入实例")
    void injectedWins() {
        ObservationRegistry registry = ObservationRegistry.create();
        assertSame(registry, ObservationWiring.effective(registry));
    }

    @Test
    @DisplayName("缺席时回退 ObservationRegistry.NOOP")
    void fallsBackToNoop() {
        assertEquals(ObservationRegistry.NOOP, ObservationWiring.effective(null));
    }
}
