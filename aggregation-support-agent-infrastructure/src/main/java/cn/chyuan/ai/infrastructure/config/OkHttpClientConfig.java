package cn.chyuan.ai.infrastructure.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 共享 OkHttpClient 配置 — 统一管理 HTTP 连接池和超时设置
 * <p>
 * 避免每个 Gateway/Service 各自创建 OkHttpClient 实例，浪费连接资源。
 * 所有需要 HTTP 调用的服务（嵌入网关、Rerank服务等）共用此 Bean。
 */
@Configuration
public class OkHttpClientConfig {

    @Bean
    public OkHttpClient httpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
                .build();
    }
}
