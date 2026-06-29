package cn.chyuan.ai.infrastructure.gateway.memory;

import cn.chyuan.ai.domain.memory.adapter.port.IMemoryConsolidationGateway;
import cn.chyuan.ai.domain.memory.model.enums.ConsolidationAction;
import cn.chyuan.ai.domain.memory.model.valobj.ConsolidationDecision;
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
        .readTimeout(600, TimeUnit.SECONDS)
        .build();
    
    /**
     * Consolidation 决策 Prompt
     */
    private static final String CONSOLIDATION_PROMPT = """
        你是一个记忆管理助手。判断两条信息的关系，并通过 submit_consolidation_decision 工具提交决策。

        现有信息：{existing}
        新信息：{new_info}

        决策规则：
        - KEEP: 两条信息不相关或新信息是已有信息的子集
        - UPDATE: 新信息包含更准确/更新的版本，应替换现有
        - DELETE: 新信息表明现有信息已过时或错误
        - MERGE: 两条信息互补，应合并为一条更完整的记录

        请务必调用 submit_consolidation_decision 工具提交决策；mergedContent 仅在 action 为 UPDATE 或 MERGE 时填写合并后的完整内容。
        若因工具不可用等原因无法调用工具，请在回复正文直接输出相同结构的 JSON（含 action/reason/mergedContent 字段），不要输出多余解释。
        """;

    /**
     * Function Call 工具名 —— 强制模型以工具参数形式返回结构化决策，从根源上避免小模型生成格式错误的 JSON
     */
    private static final String CONSOLIDATION_FUNCTION_NAME = "submit_consolidation_decision";

    /**
     * Function Call 参数 Schema（JSON Schema），action 通过 enum 约束为合法枚举值
     */
    private static final Map<String, Object> CONSOLIDATION_FUNCTION_SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "action", Map.of(
                "type", "string",
                "enum", List.of("KEEP", "UPDATE", "DELETE", "MERGE"),
                "description", "整合动作"
            ),
            "mergedContent", Map.of(
                "type", "string",
                "description", "合并后的完整内容（仅当 action 为 UPDATE 或 MERGE 时提供）"
            ),
            "reason", Map.of(
                "type", "string",
                "description", "做出该决策的原因"
            )
        ),
        "required", List.of("action", "reason")
    );

    /**
     * 降级路径追加的强约束指令 —— Function Call 未命中时，强制模型直接在正文输出 JSON
     * <p>
     * 轻量模型（早期 glm-4-flash 曾出现）在 auto tool_choice 下偶发"既不调用工具、content 也为空"的空响应，
     * 此时去掉 tools，用本指令追加到 prompt 末尾重试一次，避免整合功能实质失效（始终走兜底 KEEP）。
     */
    private static final String FALLBACK_JSON_SUFFIX = """

        【重要】请直接在回复正文输出 JSON，不要调用任何工具，不要输出任何多余解释或前后缀。
        JSON 格式严格如下（action 必须是 KEEP/UPDATE/DELETE/MERGE 之一）：
        {"action": "KEEP", "reason": "决策原因", "mergedContent": "合并后的完整内容（仅当 action 为 UPDATE 或 MERGE 时填写，否则省略该字段）"}
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
     * 调用 LLM 获取整合决策
     * <p>
     * 主路径：Function Call 模式（tools + tool_choice=auto），让模型通过 submit_consolidation_decision
     * 返回结构化决策，从根源上避免小模型手写 JSON 的格式错误。
     * <p>
     * 降级路径：当主路径既未命中 FC、content 也为空时（轻量模型偶发空响应，作为防御性兜底），
     * 去掉 tools 用纯文本 JSON 模式重试一次，避免整合功能实质失效（始终走兜底 KEEP）。
     */
    private String callLlm(String prompt) {
        try {
            // 主路径：Function Call 模式
            String payload = executeChat(buildRequestBody(prompt, true));
            if (payload != null && !payload.isBlank()) {
                return payload;
            }
            // 降级路径：纯文本 JSON 模式重试一次
            log.warn("整合决策主路径返回空（未命中FC且content为空），降级纯文本JSON模式重试");
            String fallbackPayload = executeChat(buildRequestBody(prompt, false));
            if (fallbackPayload != null && !fallbackPayload.isBlank()) {
                return fallbackPayload;
            }
            throw new RuntimeException("LLM 主路径与降级路径均返回空");
        } catch (IOException e) {
            throw new RuntimeException("LLM 调用异常: " + e.getMessage(), e);
        }
    }

    /**
     * 构造请求体
     *
     * @param withTools true=Function Call 主路径（带 tools + tool_choice=auto）；
     *                  false=纯文本降级路径（不带 tools，prompt 末尾追加 JSON 强约束）
     */
    private Map<String, Object> buildRequestBody(String prompt, boolean withTools) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("temperature", temperature);

        if (withTools) {
            requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
            ));
            // 通过 Function Call 强制模型以工具参数形式返回结构化决策，从根源上避免小模型生成格式错误的 JSON
            requestBody.put("tools", List.of(
                Map.of(
                    "type", "function",
                    "function", Map.of(
                        "name", CONSOLIDATION_FUNCTION_NAME,
                        "description", "提交两条相似记忆的整合决策结果",
                        "parameters", CONSOLIDATION_FUNCTION_SCHEMA
                    )
                )
            ));
            // 智谱 BigModel 的 tool_choice 官方仅支持 "auto"（不支持 OpenAI 强制指定函数的对象格式），
            // 此处显式声明 auto，配合"单工具 + 强约束 prompt"引导模型命中整合函数
            requestBody.put("tool_choice", "auto");
        } else {
            // 降级路径：去掉 tools，prompt 末尾追加 JSON 强约束，要求模型直接在正文输出 JSON
            requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt + FALLBACK_JSON_SUFFIX)
            ));
        }
        return requestBody;
    }

    /**
     * 执行单次 LLM 调用并提取决策载荷
     * <p>
     * 优先取 Function Call 的 arguments（模型按 schema 生成的合法 JSON）；未命中 FC 时取 content 文本；
     * 两者均无时返回 null（由调用方决定是否降级重试）。同时记录 finish_reason，便于诊断空响应根因。
     *
     * @return 决策文本（arguments 或 content）；无可用载荷时返回 null
     */
    private String executeChat(Map<String, Object> requestBody) throws IOException {
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
            if (choices == null || choices.isEmpty()) {
                return null;
            }

            Map<String, Object> choice = choices.get(0);
            Object finishReason = choice.get("finish_reason");
            var message = (Map<String, Object>) choice.get("message");
            if (message == null) {
                return null;
            }

            // 优先从 Function Call 的 arguments 中提取结构化决策（模型按 schema 生成的合法 JSON）
            var toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
            if (toolCalls != null && !toolCalls.isEmpty()) {
                var function = (Map<String, Object>) toolCalls.get(0).get("function");
                return (String) function.get("arguments");
            }

            // 未命中 FC，回退 content 文本解析；记录 finish_reason 便于监控命中率与定位空响应根因（length/content_filter 等）
            String content = (String) message.get("content");
            if (content == null || content.isBlank()) {
                log.warn("整合决策响应无可用载荷: finish_reason={}, 无 tool_calls 且 content 为空", finishReason);
                return null;
            }
            log.warn("整合决策未命中 Function Call，回退 content 文本解析: finish_reason={}", finishReason);
            return content;
        }
    }
    
    /**
     * 构造默认保留决策（安全兜底，避免记忆丢失）
     */
    private ConsolidationDecision defaultKeep(String reason) {
        return ConsolidationDecision.builder()
            .action(ConsolidationAction.KEEP)
            .reason(reason)
            .build();
    }

    /**
     * 解析整合决策
     */
    private ConsolidationDecision parseDecision(String llmResponse) {
        try {
            // LLM 未返回有效内容（arguments/content 均为空）时显式短路，避免后续 NPE 控制流
            if (llmResponse == null || llmResponse.isBlank()) {
                log.warn("解析整合决策失败: LLM 未返回有效内容，默认保留");
                return defaultKeep("LLM 未返回有效决策，默认保留");
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

            // 使用 JsonNode 树模型解析，避免 LinkedHashMap 强转 String 的 ClassCastException
            JsonNode rootNode = objectMapper.readTree(json);

            String actionStr = rootNode.has("action") && !rootNode.get("action").isNull()
                    ? rootNode.get("action").asText() : null;
            String reason = rootNode.has("reason") && !rootNode.get("reason").isNull()
                    ? rootNode.get("reason").asText() : null;

            // mergedContent 可能是字符串或嵌套对象，统一转为字符串
            String mergedContent = null;
            if (rootNode.has("mergedContent") && !rootNode.get("mergedContent").isNull()) {
                JsonNode mergedNode = rootNode.get("mergedContent");
                if (mergedNode.isTextual()) {
                    mergedContent = mergedNode.asText();
                } else {
                    // 嵌套对象/数组序列化回 JSON 字符串
                    mergedContent = objectMapper.writeValueAsString(mergedNode);
                }
            }

            ConsolidationAction action = ConsolidationAction.KEEP;
            if (actionStr != null) {
                try {
                    action = ConsolidationAction.valueOf(actionStr.toUpperCase());
                } catch (Exception e) {
                    log.warn("未知整合动作，回退 KEEP: actionStr={}", actionStr);
                }
            }

            // UPDATE/MERGE 必须提供 mergedContent，否则下游 updateMemory 计算 hash 会 NPE；此处降级为 KEEP
            if ((action == ConsolidationAction.UPDATE || action == ConsolidationAction.MERGE)
                    && (mergedContent == null || mergedContent.isBlank())) {
                log.warn("action={} 但 mergedContent 为空，回退 KEEP", action);
                action = ConsolidationAction.KEEP;
            }

            return ConsolidationDecision.builder()
                .action(action)
                .mergedContent(mergedContent)
                .reason(reason)
                .build();

        } catch (Exception e) {
            log.warn("解析整合决策失败: {}", e.getMessage());
            return defaultKeep("解析失败，默认保留");
        }
    }
}
