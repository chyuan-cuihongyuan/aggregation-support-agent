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
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DashScope 嵌入模型网关 — 使用 DashScope OpenAI 兼容接口调用 text-embedding-v4 模型计算文本向量
 * <p>
 * 通过 DashScope 的 OpenAI 兼容 HTTP API（/v1/embeddings）调用文本嵌入模型，
 * 将文本转换为 1024 维浮点向量，用于后续的向量相似性检索。
 * <p>
 * 配置项：
 * <ul>
 *   <li>dashscope.api.key: DashScope API 密钥（必填）</li>
 *   <li>dashscope.embedding.model: 嵌入模型名称，默认 text-embedding-v4</li>
 * </ul>
 */
@Slf4j
@Service
public class DashScopeEmbeddingGateway implements IEmbeddingService {

    /** DashScope OpenAI 兼容接口地址 */
    private static final String BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.embedding.model:text-embedding-v4}")
    private String modelName;

    /** HTTP 客户端 — 设置合理的超时时间 */
    private OkHttpClient httpClient;

    /**
     * 初始化 OkHttpClient — 设置连接、读写超时时间
     */
    @PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        log.info("DashScope 嵌入网关初始化完成: model={}", modelName);
    }

    /**
     * 单文本嵌入 — 将一段文本转换为 1024 维向量
     * <p>
     * 调用 DashScope text-embedding-v4 模型，将输入文本转换为语义向量，
     * 用于后续在 Milvus 中的相似性检索。
     *
     * @param text 输入文本
     * @return 1024 维浮点数组表示的向量
     */
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

    /**
     * 批量文本嵌入 — 将多段文本一次性转换为向量，减少 API 调用次数
     * <p>
     * 通过一次 HTTP 请求将多段文本发送给 DashScope，
     * 利用批量接口减少网络往返，提高嵌入效率。
     *
     * @param texts 输入文本列表
     * @return 向量列表，与输入顺序一一对应
     */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            log.warn("批量嵌入文本列表为空，返回空列表");
            return Collections.emptyList();
        }

        try {
            // 构建请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", modelName);

            // 构建输入文本数组
            JSONArray inputArray = new JSONArray();
            inputArray.addAll(texts);
            requestBody.put("input", inputArray);

            // 构建 HTTP 请求
            Request request = new Request.Builder()
                    .url(BASE_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody.toJSONString(), JSON_MEDIA_TYPE))
                    .build();

            // 发送请求并解析响应
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorMsg = response.body() != null ? response.body().string() : "unknown error";
                    log.error("DashScope 嵌入 API 调用失败: status={}, body={}", response.code(), errorMsg);
                    throw new RuntimeException("DashScope 嵌入 API 调用失败: HTTP " + response.code());
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                return parseEmbeddingResponse(responseBody);
            }

        } catch (IOException e) {
            log.error("DashScope 嵌入 API 网络异常: {}", e.getMessage(), e);
            throw new RuntimeException("DashScope 嵌入 API 网络异常", e);
        } catch (Exception e) {
            log.error("DashScope 嵌入 API 调用异常: {}", e.getMessage(), e);
            throw new RuntimeException("DashScope 嵌入 API 调用异常", e);
        }
    }

    /**
     * 解析 DashScope 嵌入 API 的响应体 — 从 JSON 中提取向量数据
     * <p>
     * 响应格式示例：
     * <pre>
     * {
     *   "data": [
     *     {"embedding": [0.1, 0.2, ...], "index": 0},
     *     {"embedding": [0.3, 0.4, ...], "index": 1}
     *   ],
     *   "model": "text-embedding-v4",
     *   "usage": {"total_tokens": 100}
     * }
     * </pre>
     *
     * @param responseBody API 返回的 JSON 字符串
     * @return 向量列表，按 index 字段排序
     */
    private List<float[]> parseEmbeddingResponse(String responseBody) {
        JSONObject jsonResponse = JSON.parseObject(responseBody);
        JSONArray dataArray = jsonResponse.getJSONArray("data");

        if (dataArray == null || dataArray.isEmpty()) {
            log.error("DashScope 嵌入 API 返回数据为空: {}", responseBody);
            return Collections.emptyList();
        }

        // 按 index 排序确保与输入顺序一致
        List<JSONObject> sortedData = new ArrayList<>();
        for (int i = 0; i < dataArray.size(); i++) {
            sortedData.add(dataArray.getJSONObject(i));
        }
        sortedData.sort((a, b) -> a.getInteger("index").compareTo(b.getInteger("index")));

        // 提取向量数据
        List<float[]> vectors = new ArrayList<>();
        for (JSONObject item : sortedData) {
            JSONArray embeddingArray = item.getJSONArray("embedding");
            float[] vector = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                vector[i] = embeddingArray.getFloatValue(i);
            }
            vectors.add(vector);
        }

        log.info("DashScope 嵌入完成: vectorCount={}, dimension={}",
                vectors.size(), vectors.isEmpty() ? 0 : vectors.get(0).length);
        return vectors;
    }

}
