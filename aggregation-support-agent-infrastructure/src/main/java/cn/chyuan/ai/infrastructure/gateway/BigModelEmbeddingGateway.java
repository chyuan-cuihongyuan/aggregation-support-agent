package cn.chyuan.ai.infrastructure.gateway;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.types.exception.EmbeddingException;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 智谱 BigModel 嵌入模型网关 — 使用 OpenAI 兼容接口调用 embedding-3 模型计算文本向量
 * <p>
 * 通过智谱 BigModel 的 HTTP API（/embeddings）调用文本嵌入模型，
 * 将文本转换为指定维度浮点向量，用于后续的向量相似性检索。
 * <p>
 * 配置项：
 * <ul>
 *   <li>bigmodel.api.key: 智谱 API 密钥（必填）</li>
 *   <li>bigmodel.embedding.base-url: 嵌入接口地址</li>
 *   <li>bigmodel.embedding.model: 嵌入模型名称，默认 embedding-3</li>
 *   <li>bigmodel.embedding.dimension: 输出向量维度，默认 1024</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "embedding.provider", havingValue = "bigmodel", matchIfMissing = true)
public class BigModelEmbeddingGateway implements IEmbeddingService {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    @Value("${bigmodel.api.key}")
    private String apiKey;

    @Value("${bigmodel.embedding.base-url}")
    private String baseUrl;

    @Value("${bigmodel.embedding.model}")
    private String modelName;

    @Value("${bigmodel.embedding.dimension}")
    private int dimension;

    /**
     * 单条文本最大输入字符数（超出截断）。
     * <p>
     * 智谱 embedding-3 单条输入上限约 512 token，超长会返回 HTTP 400 / code=1210「API 调用参数有误」。
     * 按中英文混合约 3 字符/token 估算，1500 字符 ≈ 500 token，留余量避免触发上限。
     * EPISODE 记忆存储整段「用户问 + 助手答」对话时极易超限，必须在网关层兜底截断。
     */
    @Value("${bigmodel.embedding.max-input-chars:1500}")
    private int maxInputChars;

    @Resource
    private OkHttpClient httpClient;

    @Override
    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            log.warn("嵌入文本为空，返回零向量");
            return new float[0];
        }

        List<float[]> results = embedBatch(Collections.singletonList(text));
        if (results.isEmpty()) {
            log.error("单文本嵌入结果为空");
            return new float[0];
        }
        return results.get(0);
    }

    @Override
    public int dimension() {
        return dimension;
    }

    /** 智谱嵌入 API 单次最大批量数 */
    private static final int MAX_BATCH_SIZE = 64;

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            log.warn("批量嵌入文本列表为空，返回空列表");
            return Collections.emptyList();
        }

        // 预处理：空白文本跳过（智谱 API 不接受空 input，会返回 1210）；
        // 超长文本截断到 maxInputChars，避免超过 embedding-3 单条 token 上限触发 1210。
        // 维护原始 index 对齐，保证返回向量与入参一一对应（空白位以零向量占位）。
        List<float[]> results = new ArrayList<>(Collections.nCopies(texts.size(), new float[0]));
        List<String> effectiveTexts = new ArrayList<>();
        List<Integer> effectiveIndices = new ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.trim().isEmpty()) {
                continue; // 保持零向量占位
            }
            if (text.length() > maxInputChars) {
                log.warn("嵌入文本超长已截断: originalLen={}, maxLen={}", text.length(), maxInputChars);
                text = text.substring(0, maxInputChars);
            }
            effectiveTexts.add(text);
            effectiveIndices.add(i);
        }

        if (effectiveTexts.isEmpty()) {
            return results;
        }

        // 分片调用，每批最多 MAX_BATCH_SIZE 条
        for (int i = 0; i < effectiveTexts.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, effectiveTexts.size());
            List<String> batch = new ArrayList<>(effectiveTexts.subList(i, end));
            log.info("智谱嵌入分片调用: batch={}/{}, count={}", (i / MAX_BATCH_SIZE) + 1,
                    (effectiveTexts.size() + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE, batch.size());
            List<float[]> batchResults = doEmbedBatch(batch);
            for (int j = 0; j < batchResults.size() && (i + j) < effectiveIndices.size(); j++) {
                results.set(effectiveIndices.get(i + j), batchResults.get(j));
            }
        }
        return results;
    }

    private List<float[]> doEmbedBatch(List<String> texts) {
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", modelName);
            requestBody.put("dimensions", dimension);

            JSONArray inputArray = new JSONArray();
            inputArray.addAll(texts);
            requestBody.put("input", inputArray);

            Request request = new Request.Builder()
                    .url(baseUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody.toJSONString(), JSON_MEDIA_TYPE))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorMsg = response.body() != null ? response.body().string() : "unknown error";
                    log.error("智谱嵌入 API 调用失败: status={}, body={}", response.code(), errorMsg);

                    // 根据状态码和响应体推断失败类型，支持上游降级决策
                    EmbeddingException.FailureType failureType =
                            EmbeddingException.FailureType.fromHttpStatus(response.code(), errorMsg);
                    throw new EmbeddingException(failureType, "bigmodel", response.code(), errorMsg);
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                return parseEmbeddingResponse(responseBody);
            }

        } catch (EmbeddingException e) {
            // 结构化异常直接抛出，不二次包装
            throw e;
        } catch (IOException e) {
            log.error("智谱嵌入 API 网络异常: {}", e.getMessage(), e);
            throw new EmbeddingException(EmbeddingException.FailureType.NETWORK_ERROR, "bigmodel", e.getMessage(), e);
        } catch (Exception e) {
            log.error("智谱嵌入 API 调用异常: {}", e.getMessage(), e);
            throw new EmbeddingException(EmbeddingException.FailureType.UNKNOWN, "bigmodel", e.getMessage(), e);
        }
    }

    private List<float[]> parseEmbeddingResponse(String responseBody) {
        JSONObject jsonResponse = JSON.parseObject(responseBody);
        JSONArray dataArray = jsonResponse.getJSONArray("data");

        if (dataArray == null || dataArray.isEmpty()) {
            log.error("智谱嵌入 API 返回数据为空: {}", responseBody);
            return Collections.emptyList();
        }

        List<JSONObject> sortedData = new ArrayList<>();
        for (int i = 0; i < dataArray.size(); i++) {
            sortedData.add(dataArray.getJSONObject(i));
        }
        sortedData.sort((a, b) -> a.getInteger("index").compareTo(b.getInteger("index")));

        List<float[]> vectors = new ArrayList<>();
        for (JSONObject item : sortedData) {
            JSONArray embeddingArray = item.getJSONArray("embedding");
            float[] vector = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                vector[i] = embeddingArray.getFloatValue(i);
            }
            vectors.add(vector);
        }

        log.info("智谱嵌入完成: vectorCount={}, dimension={}",
                vectors.size(), vectors.isEmpty() ? 0 : vectors.get(0).length);
        return vectors;
    }

}
