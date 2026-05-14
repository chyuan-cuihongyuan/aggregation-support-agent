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

/**
 * Web MVC 配置 — 统一配置 CORS 跨域和异步超时
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 允许的前端来源，多个用逗号分隔，可通过 application.yml 配置 */
    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    /**
     * CORS 跨域过滤器 — 使用 CorsFilter 而不是 registry.addMapping
     * 这种方式更可靠，能确保 OPTIONS 预检请求被正确处理
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 允许的前端地址
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));

        // 允许所有请求头（重要：允许自定义头）
        config.setAllowedHeaders(Arrays.asList("*"));

        // 允许所有请求方法
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));

        // 允许携带 Cookie（用于认证）
        config.setAllowCredentials(true);

        // 预检请求的有效期（秒）
        config.setMaxAge(3600L);

        // 对所有路径应用 CORS 配置
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(10 * 60 * 1000L);
    }
}
