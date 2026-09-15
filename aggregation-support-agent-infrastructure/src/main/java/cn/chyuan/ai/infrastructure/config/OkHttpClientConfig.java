package cn.chyuan.ai.infrastructure.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

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

    /** 嵌入短请求语义：5s 充裕（正常延迟 <2s）；原默认 30 超 G49 钳位上限，显式调对 */
    @Value("${http.embedding.connect-timeout-seconds:5}")
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
                .addInterceptor(userAgentInterceptor("agent-chyuan-aggregation"))
                .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
                .build();
    }

    /** 出站 UA 注入（T03）：下游排障可识别调用方（app 名 + 版本） */
    static okhttp3.Interceptor userAgentInterceptor(String appName) {
        return chain -> {
            okhttp3.Request req = chain.request().newBuilder()
                    .header("User-Agent", appName + "/1.0")
                    .build();
            return chain.proceed(req);
        };
    }

    /**
     * 嵌入专用 client：双向钳位（G49）——下限 1s 防无超时，上限防误配
     * 使降级链预算（loop-414 判据 ≤3min ⇒ read ≤30s）回归无上界。
     */
    @Bean
    public OkHttpClient embeddingHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(clamp(embeddingConnectTimeoutSeconds, 1, 15), TimeUnit.SECONDS)
                .readTimeout(clamp(embeddingReadTimeoutSeconds, 1, 30), TimeUnit.SECONDS)
                .writeTimeout(clamp(embeddingWriteTimeoutSeconds, 1, 30), TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build();
    }

    /** 边界钳位：越界取边界值，界内透传 */
    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
