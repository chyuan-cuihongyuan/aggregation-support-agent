package cn.chyuan.ai.infrastructure.gateway.memory;

import cn.chyuan.ai.domain.memory.adapter.port.IMemoryExtractionGateway;
import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.ExtractedFact;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 记忆提取服务 - 使用 LLM 从对话中提取原子事实
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agent.memory.enabled", havingValue = "true", matchIfMissing = false)
public class MemoryExtractionService implements IMemoryExtractionGateway {
    
    @Value("${ai-api.base-url}")
    private String baseUrl;
    
    @Value("${ai-api.api-key}")
    private String apiKey;
    
    @Value("${agent.memory.extraction.model}")
    private String model;
    
    @Value("${agent.memory.extraction.temperature}")
    private double temperature;
    
    @Resource
    private ObjectMapper objectMapper;
    
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build();
    
    /**
     * 事实提取 Prompt
     */
    private static final String EXTRACTION_PROMPT = """
        从以下对话内容中提取独立的、原子化的事实陈述。
        
        要求：
        1. 每个事实应该是自包含的，不依赖上下文
        2. 保留关键细节（人名、时间、数字等）
        3. 忽略寒暄和无意义内容
        4. 每个事实不超过200字
        
        请严格按照以下 JSON 格式输出，不要包含任何其他内容：
        [{"content": "事实内容", "type": "FACT|PREFERENCE|DECISION|EPISODE|KNOWLEDGE"}]
        
        类型说明：
        - FACT: 客观事实
        - PREFERENCE: 用户偏好
        - DECISION: 决策或结论
        - EPISODE: 事件片段
        - KNOWLEDGE: 知识信息
        
        内容：
        %s
        """;
    
    /**
     * 重要性评估 Prompt
     */
    private static final String IMPORTANCE_PROMPT = """
        评估以下信息的重要性，返回 0-1 之间的分数：
        
        评分标准：
        - 0.9-1.0: 关键决策、安全信息、用户核心偏好
        - 0.7-0.8: 业务规则、重要配置、明确需求
        - 0.5-0.6: 一般事实、常规信息
        - 0.1-0.4: 闲聊、临时信息、已过时内容
        
        只返回数字，不要其他内容。
        
        内容：%s
        """;
    
    /**
     * 从内容中提取原子事实
     */
    public List<ExtractedFact> extractFacts(String content) {
        try {
            String prompt = String.format(EXTRACTION_PROMPT, content);
            String response = callLlm(prompt);
            return parseFacts(response);
        } catch (Exception e) {
            log.warn("提取事实失败，使用原始内容", e);
            return List.of(ExtractedFact.builder()
                .content(content)
                .type(MemoryType.FACT)
                .build());
        }
    }
    
    /**
     * 评估内容重要性
     */
    public Float assessImportance(String content) {
        try {
            String prompt = String.format(IMPORTANCE_PROMPT, content);
            String response = callLlm(prompt);
            return Float.parseFloat(response.trim().replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            log.warn("评估重要性失败，使用默认值", e);
            return 0.5f;
        }
    }
    
    /**
     * 调用 LLM
     */
    private String callLlm(String prompt) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
            ));
            requestBody.put("temperature", temperature);
            
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
                Map<String, Object> result = objectMapper.readValue(responseBody, new TypeReference<>() {});
                var choices = (List<Map<String, Object>>) result.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    var message = (Map<String, Object>) choices.get(0).get("message");
                    return (String) message.get("content");
                }
                throw new RuntimeException("LLM 返回为空");
            }
        } catch (IOException e) {
            throw new RuntimeException("LLM 调用异常: " + e.getMessage(), e);
        }
    }
    
    /**
     * 解析提取的事实
     */
    private List<ExtractedFact> parseFacts(String llmResponse) {
        try {
            // 提取 JSON 部分
            String json = llmResponse;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7);
                json = json.substring(0, json.indexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3);
                json = json.substring(0, json.indexOf("```"));
            }
            json = json.trim();
            
            List<Map<String, Object>> factsList = objectMapper.readValue(json, new TypeReference<>() {});
            List<ExtractedFact> facts = new ArrayList<>();
            
            for (Map<String, Object> factMap : factsList) {
                String content = (String) factMap.get("content");
                String typeStr = (String) factMap.get("type");
                
                if (content != null && content.length() > 10) {
                    MemoryType type = MemoryType.FACT;
                    try {
                        type = MemoryType.valueOf(typeStr.toUpperCase());
                    } catch (Exception e) {
                        // 默认使用 FACT 类型
                    }
                    
                    facts.add(ExtractedFact.builder()
                        .content(content)
                        .type(type)
                        .build());
                }
            }
            
            return facts;
            
        } catch (Exception e) {
            log.warn("解析 LLM 提取结果失败: {}", e.getMessage());
            return List.of();
        }
    }
}
