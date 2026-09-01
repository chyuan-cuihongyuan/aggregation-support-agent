package cn.chyuan.ai.infrastructure.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 查询优化专用 ChatModel 配置
 * <p>
 * 使用轻量级模型（glm-4.5-flash）供 QueryOptimizationService 进行
 * 查询改写、HyDE 生成等操作。
 * <p>
 * 独立于智能体装配系统的动态 ChatModel，避免 @Autowired(required=false) 注入为 null。
 */
@Slf4j
@Configuration
public class QueryOptimizationConfig {

    @Value("${ai-api.base-url}")
    private String baseUrl;

    @Value("${ai-api.api-key}")
    private String apiKey;

    @Value("${rag.query.chat-model:glm-4.5-flash}")
    private String model;

    /**
     * 查询优化专用 ChatModel Bean
     * 仅在查询改写启用时才创建
     */
    @Bean("queryOptimizationChatModel")
    @ConditionalOnProperty(name = "rag.query.rewrite.enabled", havingValue = "true")
    public ChatModel queryOptimizationChatModel() {
        log.info("初始化查询优化 ChatModel: baseUrl={}, model={}", baseUrl, model);
        // Spring AI 2.0 起基于官方 openai-java SDK：客户端固定在 baseUrl 后拼 /chat/completions，
        // 旧 completionsPath=/chat/completions（空前缀）折叠后 baseUrl 原样保留，实际请求地址不变
        // （工单 0023，同 mcp-gateway 0016 口径）。octet-stream 兼容 hack 随官方 SDK 自行解析响应移除。
        OpenAIClient openAIClient = OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .timeout(Duration.ofMillis(180000))
                .build();
        return OpenAiChatModel.builder()
                .openAiClient(openAIClient)
                .options(OpenAiChatOptions.builder()
                        .model(model)
                        .build())
                .build();
    }
}
