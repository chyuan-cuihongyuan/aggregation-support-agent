package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * GraphRAG 端点默认关守卫（工单 0314 AM9）：
 * GraphRagController 必须 @ConditionalOnProperty(graphrag.enabled=true) 且无 matchIfMissing，
 * 配置缺省时端点不注册，保障零回归。
 */
class GraphRagEndpointGuardTest {

    @Test
    void 可视化端点默认关() {
        ConditionalOnProperty conditional = cn.chyuan.ai.trigger.http.GraphRagController.class
                .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(conditional, "GraphRagController 应带 @ConditionalOnProperty");
        assertEquals("graphrag.enabled", conditional.name()[0]);
        assertEquals("true", conditional.havingValue());
        assertFalse(conditional.matchIfMissing(), "缺省配置必须不注册端点");
    }
}
