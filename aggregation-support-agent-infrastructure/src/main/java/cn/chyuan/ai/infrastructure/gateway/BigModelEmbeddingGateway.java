package cn.chyuan.ai.infrastructure.gateway;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
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
import java.util.concurrent.TimeUnit;

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

    @Value("${bigmodel.embedding.base-url:https://open.bigmodel.cn/api/paas/v4/embeddings}")
    private String baseUrl;

    @Value("${bigmodel.embedding.model:embedding-3}")
    private String modelName;

    @Value("${bigmodel.embedding.dimension:1024}")
    private int dimension;

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

    /** 智谱嵌入 API 单次最大批量数 */
    private static final int MAX_BATCH_SIZE = 64;

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            log.warn("批量嵌入文本列表为空，返回空列表");
            return Collections.emptyList();
        }

        if (texts.size() <= MAX_BATCH_SIZE) {
            return doEmbedBatch(texts);
        }

        // 分片调用，每批最多 MAX_BATCH_SIZE 条
        List<float[]> allResults = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, texts.size());
            List<String> batch = new ArrayList<>(texts.subList(i, end));
            log.info("智谱嵌入分片调用: batch={}/{}, count={}", (i / MAX_BATCH_SIZE) + 1,
                    (texts.size() + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE, batch.size());
            allResults.addAll(doEmbedBatch(batch));
        }
        return allResults;
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
                    throw new RuntimeException("智谱嵌入 API 调用失败: HTTP " + response.code());
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                return parseEmbeddingResponse(responseBody);
            }

        } catch (IOException e) {
            log.error("智谱嵌入 API 网络异常: {}", e.getMessage(), e);
            throw new RuntimeException("智谱嵌入 API 网络异常", e);
        } catch (Exception e) {
            log.error("智谱嵌入 API 调用异常: {}", e.getMessage(), e);
            throw new RuntimeException("智谱嵌入 API 调用异常", e);
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
