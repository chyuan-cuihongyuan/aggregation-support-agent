package cn.chyuan.ai.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

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
        // base-url 为智谱 .../api/paas/v4，需覆盖默认补全路径 /v1/chat/completions，
        // 否则拼成 .../v4/v1/chat/completions 触发 404
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(30000);
        requestFactory.setReadTimeout(180000);

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .completionsPath("/chat/completions")
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .build())
                .build();
    }
}
