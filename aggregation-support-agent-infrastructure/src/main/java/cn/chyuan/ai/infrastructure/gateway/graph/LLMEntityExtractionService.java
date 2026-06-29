package cn.chyuan.ai.infrastructure.gateway.graph;

import cn.chyuan.ai.domain.knowledgegraph.adapter.port.IEntityExtractionService;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphEntity;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphRelation;
import cn.chyuan.ai.domain.knowledgegraph.model.valobj.EntityExtractionResultVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * LLM 实体抽取服务实现
 * 通过智谱 glm-4.5-flash（OpenAI 兼容接口）从文本中抽取实体和关系
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "knowledge-graph.extraction.enabled", havingValue = "true")
public class LLMEntityExtractionService implements IEntityExtractionService {

    @Value("${ai-api.base-url}")
    private String baseUrl;

    @Value("${ai-api.api-key}")
    private String apiKey;

    @Value("${knowledge-graph.extraction.model}")
    private String model;

    @Value("${knowledge-graph.extraction.temperature}")
    private double temperature;

    @Value("${knowledge-graph.extraction.timeout}")
    private int timeout;

    @Resource
    private ObjectMapper objectMapper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .build();

    private static final String EXTRACTION_PROMPT = """
            你是一个专业的知识图谱构建专家。请从以下文本中抽取实体和关系。

            ## 输出格式
            请严格按照以下 JSON 格式输出，不要包含任何其他内容：
            {
              "entities": [
                {
                  "name": "实体名称",
                  "type": "CONCEPT|PERSON|ORGANIZATION|TECHNOLOGY|PRODUCT|EVENT",
                  "description": "实体描述",
                  "properties": {}
                }
              ],
              "relations": [
                {
                  "source": "源实体名称",
                  "target": "目标实体名称",
                  "type": "RELATED_TO|PART_OF|DEPENDS_ON|BELONGS_TO|USES|LOCATED_IN",
                  "description": "关系描述"
                }
              ]
            }

            ## 文本内容
            %s

            ## 上下文信息
            %s

            ## 抽取规则
            1. 只抽取明确出现在文本中的实体和关系，不要推测
            2. 实体名称保持原文中的表述
            3. 关系必须连接已抽取的实体
            4. 技术类文档重点关注：技术栈、组件、配置项、故障现象、解决方案
            5. 运维类文档重点关注：系统名称、指标、告警、操作步骤
            """;

    /**
     * Function Call 工具名 —— 强制模型以工具参数形式返回结构化实体/关系，从根源上避免小模型生成格式错误的 JSON
     */
    private static final String EXTRACTION_FUNCTION_NAME = "submit_extracted_entities";

    /**
     * Function Call 参数 Schema（JSON Schema），实体/关系类型通过 enum 约束为合法枚举值
     */
    private static final Map<String, Object> EXTRACTION_FUNCTION_SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "entities", Map.of(
                "type", "array",
                "description", "抽取到的实体列表",
                "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "name", Map.of("type", "string", "description", "实体名称（保持原文表述）"),
                        "type", Map.of(
                            "type", "string",
                            "enum", List.of("CONCEPT", "PERSON", "ORGANIZATION", "TECHNOLOGY", "PRODUCT", "EVENT"),
                            "description", "实体类型"
                        ),
                        "description", Map.of("type", "string", "description", "实体描述"),
                        "properties", Map.of("type", "object", "description", "实体附加属性", "additionalProperties", true)
                    ),
                    "required", List.of("name", "type")
                )
            ),
            "relations", Map.of(
                "type", "array",
                "description", "抽取到的关系列表（关系必须连接已抽取的实体）",
                "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "source", Map.of("type", "string", "description", "源实体名称"),
                        "target", Map.of("type", "string", "description", "目标实体名称"),
                        "type", Map.of(
                            "type", "string",
                            "enum", List.of("RELATED_TO", "PART_OF", "DEPENDS_ON", "BELONGS_TO", "USES", "LOCATED_IN"),
                            "description", "关系类型"
                        ),
                        "description", Map.of("type", "string", "description", "关系描述")
                    ),
                    "required", List.of("source", "target", "type")
                )
            )
        ),
        "required", List.of("entities", "relations")
    );

    @Override
    public EntityExtractionResultVO extractEntities(String text, String context) {
        String prompt = String.format(EXTRACTION_PROMPT, text, context != null ? context : "无");
        String response = callLlm(prompt);
        return parseExtractionResult(response, 0);
    }

    @Override
    public List<EntityExtractionResultVO> batchExtract(List<String> texts, List<String> contexts) {
        List<EntityExtractionResultVO> results = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            try {
                String context = i < contexts.size() ? contexts.get(i) : null;
                results.add(extractEntities(texts.get(i), context));
            } catch (Exception e) {
                log.warn("批量抽取第{}个chunk失败: {}", i, e.getMessage());
                results.add(EntityExtractionResultVO.builder()
                        .entities(Collections.emptyList())
                        .relations(Collections.emptyList())
                        .chunkIndex(i)
                        .build());
            }
        }
        return results;
    }

    private String callLlm(String prompt) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(
                    Map.of("role", "user", "content", prompt)
            ));
            requestBody.put("temperature", temperature);
            // 通过 Function Call 强制模型以工具参数形式返回结构化实体/关系，从根源上避免小模型生成格式错误的 JSON
            requestBody.put("tools", List.of(
                    Map.of(
                        "type", "function",
                        "function", Map.of(
                            "name", EXTRACTION_FUNCTION_NAME,
                            "description", "提交从文本中抽取的实体和关系结果",
                            "parameters", EXTRACTION_FUNCTION_SCHEMA
                        )
                    )
            ));
            // 智谱 BigModel 的 tool_choice 官方仅支持 "auto"，配合"单工具 + 强约束 prompt"引导模型命中
            requestBody.put("tool_choice", "auto");

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
                    // 优先从 Function Call 的 arguments 中提取结构化结果（模型按 schema 生成的合法 JSON）
                    var toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        var function = (Map<String, Object>) toolCalls.get(0).get("function");
                        return (String) function.get("arguments");
                    }
                    // 未命中 Function Call（auto 模式下模型自主决定），回退 content 解析并告警，便于监控命中率
                    log.warn("实体抽取未命中 Function Call，回退 content 文本解析");
                    return (String) message.get("content");
                }
                throw new RuntimeException("LLM 返回为空");
            }
        } catch (IOException e) {
            throw new RuntimeException("LLM 调用异常: " + e.getMessage(), e);
        }
    }

    private EntityExtractionResultVO parseExtractionResult(String llmResponse, int chunkIndex) {
        try {
            // 提取 JSON 部分（LLM 可能返回带有 markdown 包裹的 JSON）
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
            List<Map<String, Object>> entityMaps = (List<Map<String, Object>>) parsed.get("entities");
            List<Map<String, Object>> relationMaps = (List<Map<String, Object>>) parsed.get("relations");

            List<GraphEntity> entities = new ArrayList<>();
            if (entityMaps != null) {
                for (Map<String, Object> em : entityMaps) {
                    entities.add(GraphEntity.builder()
                            .entityName((String) em.get("name"))
                            .entityType((String) em.get("type"))
                            .description((String) em.get("description"))
                            .properties((Map<String, Object>) em.get("properties"))
                            .build());
                }
            }

            List<GraphRelation> relations = new ArrayList<>();
            if (relationMaps != null) {
                for (Map<String, Object> rm : relationMaps) {
                    relations.add(GraphRelation.builder()
                            .sourceEntityName((String) rm.get("source"))
                            .targetEntityName((String) rm.get("target"))
                            .relationType((String) rm.get("type"))
                            .description((String) rm.get("description"))
                            .build());
                }
            }

            return EntityExtractionResultVO.builder()
                    .entities(entities)
                    .relations(relations)
                    .rawLlmResponse(llmResponse)
                    .chunkIndex(chunkIndex)
                    .build();
        } catch (Exception e) {
            log.warn("解析 LLM 抽取结果失败: {}", e.getMessage());
            return EntityExtractionResultVO.builder()
                    .entities(Collections.emptyList())
                    .relations(Collections.emptyList())
                    .rawLlmResponse(llmResponse)
                    .chunkIndex(chunkIndex)
                    .build();
        }
    }
}
