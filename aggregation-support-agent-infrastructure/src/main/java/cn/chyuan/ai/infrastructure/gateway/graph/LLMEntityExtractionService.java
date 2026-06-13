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
 * 通过智谱 glm-4-flash（OpenAI 兼容接口）从文本中抽取实体和关系
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

    @Override
    public EntityExtractionResultVO extractEntities(String text, String context) {
        String prompt = String.format(EXTRACTION_PROMPT, text, context != null ? context : "无");
        String response = callLlm(prompt);
        return parseExtractionResult(response, 0);
    }

    @Override
    public List<EntityExtractionResultVO> batchExtract(List<String> texts, List<String> contexts) {
        List<EntityExtractionResultVO> results = new ArrayList<>();
        int emptyCount = 0;
        int failCount = 0;
        for (int i = 0; i < texts.size(); i++) {
            try {
                String context = i < contexts.size() ? contexts.get(i) : null;
                EntityExtractionResultVO result = extractEntities(texts.get(i), context);
                results.add(result);
                if (result.getEntities().isEmpty()) {
                    emptyCount++;
                }
            } catch (Exception e) {
                failCount++;
                log.error("批量抽取第{}个chunk失败: {}", i, e.getMessage(), e);
                results.add(EntityExtractionResultVO.builder()
                        .entities(Collections.emptyList())
                        .relations(Collections.emptyList())
                        .chunkIndex(i)
                        .build());
            }
        }
        if (emptyCount > 0 || failCount > 0) {
            log.warn("批量抽取完成: 总chunk={}, 空结果={}, 失败={}, 成功={}",
                    texts.size(), emptyCount, failCount, texts.size() - emptyCount - failCount);
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
                    String name = (String) em.get("name");
                    String type = (String) em.get("type");
                    if (name == null || name.isBlank() || type == null || type.isBlank()) {
                        log.warn("chunk[{}] LLM 返回的实体缺少 name 或 type，跳过: {}", chunkIndex, em);
                        continue;
                    }
                    entities.add(GraphEntity.builder()
                            .entityName(name)
                            .entityType(type)
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

            EntityExtractionResultVO result = EntityExtractionResultVO.builder()
                    .entities(entities)
                    .relations(relations)
                    .rawLlmResponse(llmResponse)
                    .chunkIndex(chunkIndex)
                    .build();

            // 记录空抽取结果，便于排查
            if (entities.isEmpty() && relations.isEmpty()) {
                log.warn("chunk[{}] LLM 抽取结果为空，原始返回（前500字符）: {}", chunkIndex,
                        llmResponse != null ? llmResponse.substring(0, Math.min(500, llmResponse.length())) : "null");
            }

            return result;
        } catch (Exception e) {
            log.error("解析 LLM 抽取结果失败: chunkIndex={}, error={}, 原始返回（前300字符）: {}",
                    chunkIndex, e.getMessage(),
                    llmResponse != null ? llmResponse.substring(0, Math.min(300, llmResponse.length())) : "null");
            return EntityExtractionResultVO.builder()
                    .entities(Collections.emptyList())
                    .relations(Collections.emptyList())
                    .rawLlmResponse(llmResponse)
                    .chunkIndex(chunkIndex)
                    .build();
        }
    }
}
