package cn.chyuan.ai.infrastructure.gateway.memory;

import cn.chyuan.ai.domain.agent.support.prompt.PromptRegistry;
import cn.chyuan.ai.domain.memory.adapter.port.IMemoryExtractionGateway;
import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.ExtractedFact;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
        .readTimeout(600, TimeUnit.SECONDS)
        .build();
    
    /**
     * 事实提取 Prompt
     */
    private static final String EXTRACTION_PROMPT_NAME = "memory.extraction";
    private static final String EXTRACTION_PROMPT_VERSION = "v1";
    private static final String IMPORTANCE_PROMPT_NAME = "memory.importance";
    private static final String IMPORTANCE_PROMPT_VERSION = "v1";

    static {
        PromptRegistry.register(EXTRACTION_PROMPT_NAME, EXTRACTION_PROMPT_VERSION, """
        从以下对话内容中提取独立的、原子化的事实陈述，并通过 submit_extracted_facts 工具提交。

        要求：
        1. 每个事实应该是自包含的，不依赖上下文
        2. 保留关键细节（人名、时间、数字等）
        3. 忽略寒暄和无意义内容
        4. 每个事实不超过200字

        类型说明：
        - FACT: 客观事实
        - PREFERENCE: 用户偏好
        - DECISION: 决策或结论
        - EPISODE: 事件片段
        - KNOWLEDGE: 知识信息

        请务必调用 submit_extracted_facts 工具提交提取结果。

        内容：
        %s
        """);
    }

    /**
     * Function Call 工具名 —— 强制模型以工具参数形式返回结构化事实列表，从根源上避免小模型生成格式错误的 JSON
     */
    private static final String EXTRACTION_FUNCTION_NAME = "submit_extracted_facts";

    /**
     * Function Call 参数 Schema（JSON Schema），type 通过 enum 约束为合法枚举值
     */
    private static final Map<String, Object> EXTRACTION_FUNCTION_SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "facts", Map.of(
                "type", "array",
                "description", "提取到的原子事实列表",
                "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "content", Map.of(
                            "type", "string",
                            "description", "原子事实内容（自包含，不超过200字）"
                        ),
                        "type", Map.of(
                            "type", "string",
                            "enum", List.of("FACT", "PREFERENCE", "DECISION", "EPISODE", "KNOWLEDGE"),
                            "description", "事实类型"
                        )
                    ),
                    "required", List.of("content", "type")
                )
            )
        ),
        "required", List.of("facts")
    );

    /**
     * 重要性评估 Prompt 已迁入 PromptRegistry：memory.importance@v1
     */
    static {
        PromptRegistry.register(IMPORTANCE_PROMPT_NAME, IMPORTANCE_PROMPT_VERSION, """
        评估以下信息的重要性，返回 0-1 之间的分数：

        评分标准：
        - 0.9-1.0: 关键决策、安全信息、用户核心偏好
        - 0.7-0.8: 业务规则、重要配置、明确需求
        - 0.5-0.6: 一般事实、常规信息
        - 0.1-0.4: 闲聊、临时信息、已过时内容

        只返回数字，不要其他内容。

        内容：%s
        """);
    }
    
    /**
     * 从内容中提取原子事实
     */
    public List<ExtractedFact> extractFacts(String content) {
        try {
            String prompt = String.format(PromptRegistry.current(EXTRACTION_PROMPT_NAME)
                .orElseThrow(() -> new IllegalStateException("prompt not registered: " + EXTRACTION_PROMPT_NAME)), content);
            String response = callLlmWithFunctionCall(
                prompt,
                EXTRACTION_FUNCTION_NAME,
                "提交从对话中提取的原子事实列表",
                EXTRACTION_FUNCTION_SCHEMA
            );
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
            String prompt = String.format(PromptRegistry.current(IMPORTANCE_PROMPT_NAME)
                .orElseThrow(() -> new IllegalStateException("prompt not registered: " + IMPORTANCE_PROMPT_NAME)), content);
            String response = callLlm(prompt);
            return Float.parseFloat(response.trim().replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            log.warn("评估重要性失败，使用默认值", e);
            return 0.5f;
        }
    }
    
    /**
     * 调用 LLM（纯文本输出，用于重要性评估等数字/文本场景）
     */
    private String callLlm(String prompt) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
            Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", temperature);
        return (String) executeChat(requestBody).get("content");
    }

    /**
     * 调用 LLM 并通过 Function Call 返回结构化结果（合法 JSON 字符串）
     */
    @SuppressWarnings("unchecked")
    private String callLlmWithFunctionCall(String prompt, String functionName, String functionDesc, Map<String, Object> schema) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
            Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", temperature);
        // 通过 Function Call 强制模型以工具参数形式返回结构化结果，从根源上避免小模型生成格式错误的 JSON
        requestBody.put("tools", List.of(
            Map.of(
                "type", "function",
                "function", Map.of(
                    "name", functionName,
                    "description", functionDesc,
                    "parameters", schema
                )
            )
        ));
        // 智谱 BigModel 的 tool_choice 官方仅支持 "auto"，配合"单工具 + 强约束 prompt"引导模型命中
        requestBody.put("tool_choice", "auto");

        Map<String, Object> message = executeChat(requestBody);
        // 优先从 Function Call 的 arguments 中提取结构化结果（模型按 schema 生成的合法 JSON）
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
        if (toolCalls != null && !toolCalls.isEmpty()) {
            Map<String, Object> function = (Map<String, Object>) toolCalls.get(0).get("function");
            return (String) function.get("arguments");
        }
        // 未命中 Function Call（auto 模式下模型自主决定），回退 content 解析并告警，便于监控命中率与定位静默退化
        log.warn("事实提取未命中 Function Call，回退 content 文本解析");
        String content = (String) message.get("content");
        if (content == null || content.isBlank()) {
            // content 为空时抛异常，由 extractFacts 的 catch 触发"回退原始内容"兜底，避免静默丢失记忆
            throw new RuntimeException("LLM 未命中 Function Call 且 content 为空");
        }
        return content;
    }

    /**
     * 执行 Chat 请求，返回 choices[0].message
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeChat(Map<String, Object> requestBody) {
        try {
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
                List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message == null) {
                        throw new RuntimeException("LLM 返回 message 为空");
                    }
                    return message;
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
            // LLM 未返回有效内容时显式短路，避免后续 NPE 控制流
            if (llmResponse == null || llmResponse.isBlank()) {
                log.warn("解析事实提取结果失败: LLM 未返回有效内容");
                return List.of();
            }

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

            JsonNode rootNode = objectMapper.readTree(json);
            // 兼容三种结构：Function Call 的 {"facts":[...]}、回退 content 的裸数组 [...]、单对象 {"content":...,"type":...}
            JsonNode factsNode;
            if (rootNode.isArray()) {
                factsNode = rootNode;
            } else if (rootNode.has("facts")) {
                factsNode = rootNode.get("facts");
            } else if (rootNode.has("content")) {
                // 单对象事实：包装为单元素数组
                factsNode = objectMapper.createArrayNode().add(rootNode);
            } else {
                factsNode = null;
            }
            if (factsNode == null || !factsNode.isArray()) {
                log.warn("解析事实提取结果失败: 未找到 facts 数组, response={}", llmResponse);
                return List.of();
            }

            List<ExtractedFact> facts = new ArrayList<>();
            for (JsonNode factNode : factsNode) {
                JsonNode contentNode = factNode.get("content");
                if (contentNode == null || contentNode.asText("").length() <= 10) {
                    continue;
                }
                String content = contentNode.asText();
                String typeStr = factNode.has("type") && !factNode.get("type").isNull()
                        ? factNode.get("type").asText() : null;

                MemoryType type = MemoryType.FACT;
                if (typeStr != null) {
                    try {
                        type = MemoryType.valueOf(typeStr.toUpperCase());
                    } catch (Exception e) {
                        log.warn("未知事实类型，回退 FACT: typeStr={}", typeStr);
                    }
                }

                facts.add(ExtractedFact.builder()
                    .content(content)
                    .type(type)
                    .build());
            }

            return facts;

        } catch (Exception e) {
            log.warn("解析 LLM 提取结果失败: {}", e.getMessage());
            return List.of();
        }
    }
}
