package cn.chyuan.ai.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

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

    /** spring-ai GenAI 指标装配（SELFLOOP2 loop-212）：actuator registry 缺席时回退 NOOP */
    @Autowired(required = false)
    private ObservationRegistry observationRegistry;


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

        // 智谱等 OpenAI 兼容服务商会用 application/octet-stream 返回 JSON，默认 Jackson 转换器
        // 仅支持 application/json，补充支持 application/octet-stream 以避免反序列化失败。
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory)
                .messageConverters(this::augmentJacksonConverterForOctetStream);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .completionsPath("/chat/completions")
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .observationRegistry(
                        cn.chyuan.ai.domain.agent.service.armory.support.ObservationWiring.effective(observationRegistry))
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .build())
                .build();
    }

    /**
     * 将 RestClient 默认的 Jackson 转换器替换为同时支持 application/octet-stream 的副本，
     * 以兼容用 octet-stream 返回 JSON 的 OpenAI 兼容服务商。复用原转换器的 ObjectMapper，
     * 避免影响其它请求的 JSON 解析行为，且不修改共享实例。
     */
    private void augmentJacksonConverterForOctetStream(List<HttpMessageConverter<?>> converters) {
        for (int i = 0; i < converters.size(); i++) {
            HttpMessageConverter<?> converter = converters.get(i);
            if (converter instanceof MappingJackson2HttpMessageConverter defaultJackson) {
                MappingJackson2HttpMessageConverter octetStreamAware =
                        new MappingJackson2HttpMessageConverter(defaultJackson.getObjectMapper());
                List<MediaType> mediaTypes = new ArrayList<>(defaultJackson.getSupportedMediaTypes());
                if (!mediaTypes.contains(MediaType.APPLICATION_OCTET_STREAM)) {
                    mediaTypes.add(MediaType.APPLICATION_OCTET_STREAM);
                }
                octetStreamAware.setSupportedMediaTypes(mediaTypes);
                converters.set(i, octetStreamAware);
                return;
            }
        }
    }
}
