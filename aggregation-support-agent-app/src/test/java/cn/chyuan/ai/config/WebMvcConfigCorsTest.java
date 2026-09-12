package cn.chyuan.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS 配置契约测试（SELFLOOP2 loop-207）：
 * 显式 origin 列表 + credentials=true 的组合由本测试锁定；通配符与空配置 fail-fast。
 */
@DisplayName("WebMvcConfig CORS 契约")
class WebMvcConfigCorsTest {

    @Test
    @DisplayName("多 origin 解析：trim + 去空 + 去重")
    void parsesMultipleOrigins() {
        CorsConfiguration config = WebMvcConfig.buildCorsConfiguration(
                " http://a.com ,http://b.com,,http://a.com, ");
        assertEquals(2, config.getAllowedOrigins().size());
        assertTrue(config.getAllowedOrigins().contains("http://a.com"));
        assertTrue(config.getAllowedOrigins().contains("http://b.com"));
    }

    @Test
    @DisplayName("契约：credentials=true + 预检缓存 3600s + 全方法族")
    void credentialsAndMethodsContract() {
        CorsConfiguration config = WebMvcConfig.buildCorsConfiguration("http://a.com");
        assertEquals(Boolean.TRUE, config.getAllowCredentials());
        assertEquals(3600L, config.getMaxAge());
        assertTrue(config.getAllowedMethods().containsAll(
                java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD")));
    }

    @Test
    @DisplayName("通配符 origin 在配置期被拒绝（credentials 组合非法）")
    void wildcardRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> WebMvcConfig.buildCorsConfiguration("http://a.com,*"));
        assertTrue(ex.getMessage().contains("不允许"));
    }

    @Test
    @DisplayName("空/全空白配置 fail-fast")
    void emptyRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WebMvcConfig.buildCorsConfiguration(" , ,"));
        assertThrows(IllegalArgumentException.class,
                () -> WebMvcConfig.buildCorsConfiguration(""));
    }

    @Test
    @DisplayName("checkOrigin 只放行显式列表内来源")
    void checkOriginBehavior() {
        CorsConfiguration config = WebMvcConfig.buildCorsConfiguration("http://a.com");
        assertEquals("http://a.com", config.checkOrigin("http://a.com"));
        assertEquals(null, config.checkOrigin("http://evil.com"));
        assertEquals(null, config.checkOrigin("null"));
    }
}
