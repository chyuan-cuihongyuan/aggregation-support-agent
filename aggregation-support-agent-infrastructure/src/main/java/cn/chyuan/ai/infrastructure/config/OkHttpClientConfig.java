package cn.chyuan.ai.infrastructure.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 共享 OkHttpClient 配置 — 统一管理 HTTP 连接池和超时设置
 * <p>
 * 避免每个 Gateway/Service 各自创建 OkHttpClient 实例，浪费连接资源。
 * LLM/通用面共用 {@link #httpClient()}（长 read 支撑流式对话）；
 * 嵌入网关专用 {@link #embeddingHttpClient()}（短 read，降级链预算判据 T，loop-415/G48）。
 */
@Configuration
public class OkHttpClientConfig {

    @Value("${http.shared.connect-timeout-seconds:30}")
    private int connectTimeoutSeconds;

    @Value("${http.shared.read-timeout-seconds:600}")
    private int readTimeoutSeconds;

    @Value("${http.shared.write-timeout-seconds:30}")
    private int writeTimeoutSeconds;

    @Value("${http.embedding.connect-timeout-seconds:30}")
    private int embeddingConnectTimeoutSeconds;

    /** 嵌入降级链预算判据 T（docs/audit/2026-09-embedding-fallback-budget.md）：read 10s */
    @Value("${http.embedding.read-timeout-seconds:10}")
    private int embeddingReadTimeoutSeconds;

    @Value("${http.embedding.write-timeout-seconds:30}")
    private int embeddingWriteTimeoutSeconds;

    @Bean
    public OkHttpClient httpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
                .build();
    }

    /** 嵌入专用 client：正常延迟 <2s，read 10s 足够；钳位防误配（>=1s） */
    @Bean
    public OkHttpClient embeddingHttpClient() {
        int connect = Math.max(1, embeddingConnectTimeoutSeconds);
        int read = Math.max(1, embeddingReadTimeoutSeconds);
        int write = Math.max(1, embeddingWriteTimeoutSeconds);
        return new OkHttpClient.Builder()
                .connectTimeout(connect, TimeUnit.SECONDS)
                .readTimeout(read, TimeUnit.SECONDS)
                .writeTimeout(write, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build();
    }
}
