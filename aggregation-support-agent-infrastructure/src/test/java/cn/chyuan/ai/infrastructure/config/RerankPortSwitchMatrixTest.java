package cn.chyuan.ai.infrastructure.config;

import cn.chyuan.ai.domain.rag.service.rerank.PassThroughReranker;
import cn.chyuan.ai.infrastructure.gateway.rerank.UpstreamRerankPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重排端口开关装配断言（工单 0164）
 * <p>
 * 覆盖验收：rag.rerank-provider=none|upstream 互斥装配 —
 * upstream 适配仅在显式 upstream 时装配；none（默认/缺省）装配规则降级。
 * 断言方式与 VectorEngineSwitchMatrixTest 同口径（注解反射，对 Boot 版本免疫）。
 */
class RerankPortSwitchMatrixTest {

    @Test
    void upstreamPortIsStrictOptIn() {
        ConditionalOnProperty conditional = UpstreamRerankPort.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(conditional, "UpstreamRerankPort 应挂条件装配注解");
        assertEquals("rag.rerank-provider", conditional.name()[0], "开关应为 rag.rerank-provider");
        assertEquals("upstream", conditional.havingValue(), "显式 upstream 才装配");
        assertFalse(conditional.matchIfMissing(), "缺省不得装配 upstream 适配");
    }

    @Test
    void passThroughIsDefaultFallback() throws Exception {
        Class<?> configClass = RerankPortConfig.class;
        ConditionalOnProperty conditional = configClass
                .getDeclaredMethod("passThroughReranker")
                .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(conditional, "规则降级 Bean 应挂条件装配注解");
        assertEquals("rag.rerank-provider", conditional.name()[0], "开关应为 rag.rerank-provider");
        assertEquals("none", conditional.havingValue(), "none 时装配规则降级");
        assertTrue(conditional.matchIfMissing(), "缺省（默认关）应装配规则降级");
    }

    @Test
    void twoConditionsAreMutuallyExclusive() throws Exception {
        // havingValue none / upstream 互斥：任意配置组合下 IRerankPort 至多一个实现
        ConditionalOnProperty passThrough = RerankPortConfig.class
                .getDeclaredMethod("passThroughReranker")
                .getAnnotation(ConditionalOnProperty.class);
        ConditionalOnProperty upstream = UpstreamRerankPort.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("rag.rerank-provider", passThrough.name()[0]);
        assertEquals("rag.rerank-provider", upstream.name()[0]);
        assertFalse(passThrough.havingValue().equals(upstream.havingValue()), "havingValue 必须互斥");
    }

    @Test
    void passThroughInstanceIsDomainPureImplementation() {
        // 规则降级实现来自 domain（零框架依赖），非 infrastructure 适配
        assertTrue(PassThroughReranker.class.getPackageName().startsWith("cn.chyuan.ai.domain"),
                "规则降级实现应位于 domain 层");
    }
}
