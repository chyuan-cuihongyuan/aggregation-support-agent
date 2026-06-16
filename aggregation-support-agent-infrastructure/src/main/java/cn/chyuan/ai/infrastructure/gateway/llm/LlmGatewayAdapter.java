package cn.chyuan.ai.infrastructure.gateway.llm;

import cn.chyuan.ai.domain.memory.adapter.port.ILlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * LLM 网关实现
 * 使用 OkHttp 调用大模型 API
 */
@Slf4j
@Component
public class LlmGatewayAdapter implements ILlmGateway {
    
    @Value("${ai-api.base-url}")
    private String baseUrl;
    
    @Value("${ai-api.api-key}")
    private String apiKey;
    
    @Value("${ai-api.model:glm-4-flash}")
    private String model;
    
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build();
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String call(String prompt) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", new Object[]{
                Map.of("role", "user", "content", prompt)
            });
            requestBody.put("temperature", 0.7);
            
            String json = objectMapper.writeValueAsString(requestBody);
            Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new RuntimeException("LLM 调用失败: " + response.code());
                }
                String responseBody = response.body().string();
                Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
                var choices = (java.util.List<Map<String, Object>>) result.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    var message = (Map<String, Object>) choices.get(0).get("message");
                    return (String) message.get("content");
                }
                throw new RuntimeException("LLM 返回为空");
            }
        } catch (IOException e) {
            log.error("LLM 调用异常", e);
            throw new RuntimeException("LLM 调用异常: " + e.getMessage(), e);
        }
    }
    
    @Override
    public <T> T callForJson(String prompt, Class<T> clazz) {
        String response = call(prompt);
        try {
            return objectMapper.readValue(response, clazz);
        } catch (IOException e) {
            log.error("JSON 解析失败", e);
            throw new RuntimeException("JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
