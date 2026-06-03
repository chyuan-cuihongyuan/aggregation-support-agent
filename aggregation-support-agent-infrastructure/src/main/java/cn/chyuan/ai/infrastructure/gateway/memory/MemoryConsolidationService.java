package cn.chyuan.ai.infrastructure.gateway.memory;

import cn.chyuan.ai.domain.memory.adapter.port.IMemoryConsolidationGateway;
import cn.chyuan.ai.domain.memory.model.enums.ConsolidationAction;
import cn.chyuan.ai.domain.memory.model.valobj.ConsolidationDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 记忆整合服务 - 使用 LLM 决定如何处理相似记忆
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agent.memory.enabled", havingValue = "true", matchIfMissing = false)
public class MemoryConsolidationService implements IMemoryConsolidationGateway {
    
    @Value("${ai-api.base-url}")
    private String baseUrl;
    
    @Value("${ai-api.api-key}")
    private String apiKey;
    
    @Value("${agent.memory.consolidation.model}")
    private String model;
    
    @Value("${agent.memory.consolidation.temperature}")
    private double temperature;
    
    @Resource
    private ObjectMapper objectMapper;
    
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build();
    
    /**
     * Consolidation 决策 Prompt
     */
    private static final String CONSOLIDATION_PROMPT = """
        你是一个记忆管理助手。判断两条信息的关系并做出决策。
        
        现有信息：{existing}
        新信息：{new_info}
        
        请严格按照以下 JSON 格式输出，不要包含任何其他内容：
        {
            "action": "KEEP|UPDATE|DELETE|MERGE",
            "mergedContent": "合并后的内容（仅当action为UPDATE或MERGE时）",
            "reason": "决策原因"
        }
        
        决策规则：
        - KEEP: 两条信息不相关或新信息是已有信息的子集
        - UPDATE: 新信息包含更准确/更新的版本，应替换现有
        - DELETE: 新信息表明现有信息已过时或错误
        - MERGE: 两条信息互补，应合并为一条更完整的记录
        """;
    
    /**
     * 决定如何处理相似记忆
     */
    public ConsolidationDecision decide(String existingContent, String newContent) {
        try {
            String prompt = CONSOLIDATION_PROMPT
                .replace("{existing}", existingContent)
                .replace("{new_info}", newContent);
            
            String response = callLlm(prompt);
            return parseDecision(response);
        } catch (Exception e) {
            log.warn("做出整合决策失败，默认保留", e);
            return ConsolidationDecision.builder()
                .action(ConsolidationAction.KEEP)
                .reason("LLM决策失败，默认保留")
                .build();
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
     * 解析整合决策
     */
    private ConsolidationDecision parseDecision(String llmResponse) {
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
            
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            
            String actionStr = (String) parsed.get("action");
            String mergedContent = (String) parsed.get("mergedContent");
            String reason = (String) parsed.get("reason");
            
            ConsolidationAction action = ConsolidationAction.KEEP;
            try {
                action = ConsolidationAction.valueOf(actionStr.toUpperCase());
            } catch (Exception e) {
                // 默认使用 KEEP
            }
            
            return ConsolidationDecision.builder()
                .action(action)
                .mergedContent(mergedContent)
                .reason(reason)
                .build();
            
        } catch (Exception e) {
            log.warn("解析整合决策失败: {}", e.getMessage());
            return ConsolidationDecision.builder()
                .action(ConsolidationAction.KEEP)
                .reason("解析失败，默认保留")
                .build();
        }
    }
}
