package cn.chyuan.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * Web MVC 配置 — 统一配置 CORS 跨域和异步超时。
 * CORS 契约（SELFLOOP2 loop-207）：显式 origin 列表 + credentials；拒绝通配符。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 允许的前端来源，多个用逗号分隔，可通过 application.yml 配置 */
    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    /**
     * CORS 跨域过滤器 — 使用 CorsFilter 而不是 registry.addMapping
     * 这种方式更可靠，能确保 OPTIONS 预检请求被正确处理
     */
    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildCorsConfiguration(allowedOrigins));
        return new CorsFilter(source);
    }

    /**
     * 解析 origin 列表为 CorsConfiguration（包内可见以供契约测试）。
     * 防呆：trim、去空、去重；遇 "*" 直接拒绝——allowCredentials=true 下通配 origin
     * 既违反 CORS 规范也会被浏览器拒绝，必须在配置期 fail-fast（loop-207）。
     */
    static CorsConfiguration buildCorsConfiguration(String origins) {
        List<String> parsed = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("cors.allowed-origins 未配置任何有效来源");
        }
        if (parsed.contains("*")) {
            throw new IllegalArgumentException(
                    "cors.allowed-origins 不允许 \"*\"：allowCredentials=true 下通配 origin 违反 CORS 规范，请显式列出来源");
        }

        CorsConfiguration config = new CorsConfiguration();
        // 允许的前端地址（显式列表）
        config.setAllowedOrigins(parsed);
        // 允许所有请求头（重要：允许自定义头）
        config.setAllowedHeaders(Arrays.asList("*"));
        // 允许所有请求方法
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));
        // 允许携带 Cookie（用于认证）
        config.setAllowCredentials(true);
        // 预检请求的有效期（秒）
        config.setMaxAge(3600L);
        return config;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(10 * 60 * 1000L);
    }
}
