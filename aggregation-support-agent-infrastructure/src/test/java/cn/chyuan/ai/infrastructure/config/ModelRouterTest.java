package cn.chyuan.ai.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * b-11 模型路由解析契约：路由表 &gt; 旧键 &gt; 硬编码默认 三级优先。
 */
class ModelRouterTest {

    private ModelRouter routerWith(ModelRoutingProperties props, String legacyQueryModel) {
        ModelRouter router = new ModelRouter(props);
        ReflectionTestUtils.setField(router, "legacyQueryModel", legacyQueryModel);
        return router;
    }

    @Test
    void routingTableWinsOverLegacyKey() {
        ModelRoutingProperties props = new ModelRoutingProperties();
        ModelRoutingProperties.Route route = new ModelRoutingProperties.Route();
        route.setModel("glm-5-flash");
        props.getRouting().put("query-optimization", route);

        List<String> chain = routerWith(props, "glm-4.5-flash").candidates("query-optimization");
        assertThat(chain).containsExactly("glm-5-flash");
    }

    @Test
    void fallbacksAppendAfterModelInOrder() {
        ModelRoutingProperties props = new ModelRoutingProperties();
        ModelRoutingProperties.Route route = new ModelRoutingProperties.Route();
        route.setModel("glm-5-flash");
        route.setFallbacks(List.of("glm-4-flash", "glm-4.5-air"));
        props.getRouting().put("query-optimization", route);

        List<String> chain = routerWith(props, "glm-4.5-flash").candidates("query-optimization");
        assertThat(chain).containsExactly("glm-5-flash", "glm-4-flash", "glm-4.5-air");
    }

    @Test
    void fallsBackToLegacyKeyWhenRouteAbsent() {
        ModelRoutingProperties props = new ModelRoutingProperties();
        List<String> chain = routerWith(props, "glm-4.6-air").candidates("query-optimization");
        assertThat(chain).containsExactly("glm-4.6-air");
    }

    @Test
    void fallsBackToLegacyDefaultWhenKeyMissing() {
        ModelRoutingProperties props = new ModelRoutingProperties();
        List<String> chain = routerWith(props, "glm-4.5-flash").candidates("judge");
        assertThat(chain).containsExactly("glm-4.5-flash");
    }

    @Test
    void blankRouteModelIgnoresRouteAndUsesLegacy() {
        ModelRoutingProperties props = new ModelRoutingProperties();
        ModelRoutingProperties.Route route = new ModelRoutingProperties.Route();
        route.setModel("  ");
        route.setFallbacks(List.of("glm-4-flash"));
        props.getRouting().put("query-optimization", route);

        List<String> chain = routerWith(props, "glm-4.5-flash").candidates("query-optimization");
        assertThat(chain).containsExactly("glm-4.5-flash");
    }

    @Test
    void blankFallbacksDeduplicatedAndDropped() {
        ModelRoutingProperties props = new ModelRoutingProperties();
        ModelRoutingProperties.Route route = new ModelRoutingProperties.Route();
        route.setModel("glm-5-flash");
        route.setFallbacks(List.of("", "glm-4-flash", "glm-4-flash"));
        props.getRouting().put("query-optimization", route);

        List<String> chain = routerWith(props, "glm-4.5-flash").candidates("query-optimization");
        assertThat(chain).containsExactly("glm-5-flash", "glm-4-flash");
    }
}
