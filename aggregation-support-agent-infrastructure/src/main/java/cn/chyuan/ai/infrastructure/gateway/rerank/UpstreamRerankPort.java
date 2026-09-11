package cn.chyuan.ai.infrastructure.gateway.rerank;

import cn.chyuan.ai.domain.rag.adapter.port.IRerankPort;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
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
 * 重排端口上游 API 适配（工单 0164，W2）— HTTP 调用上游 rerank 服务
 * <p>
 * rag.rerank-provider=upstream 时装配；请求体兼容既有 RerankService 口径
 * （model / query / documents / top_n，响应 results[{index, relevance_score}]）。
 * <p>
 * 降级契约：超时 / 网络 / HTTP 非 2xx / 响应解析任何异常 → 降级为
 * 原分数原序返回（传入顺序即既有相关度顺序），绝不抛出打断主链路。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rag.rerank-provider", havingValue = "upstream")
public class UpstreamRerankPort implements IRerankPort {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    @Value("${rag.rerank.api-url:}")
    private String rerankApiUrl;

    @Value("${rag.rerank.api-key:}")
    private String rerankApiKey;

    @Value("${rag.rerank.model:BAAI/bge-reranker-v2-m3}")
    private String rerankModel;

    @Value("${rag.rerank.timeout:3000}")
    private int timeout;

    @Resource
    private OkHttpClient httpClient;

    @Override
    public List<VectorSearchResultVO> rerank(String query, List<VectorSearchResultVO> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            // 构建请求体（与上游 rerank API 既有合同一致）
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", rerankModel);
            requestBody.put("query", query);
            requestBody.put("top_n", topK);
            JSONArray documents = new JSONArray();
            for (VectorSearchResultVO candidate : candidates) {
                documents.add(candidate.getContent());
            }
            requestBody.put("documents", documents);

            Request request = new Request.Builder()
                    .url(rerankApiUrl)
                    .addHeader("Authorization", "Bearer " + rerankApiKey)
                    .post(RequestBody.create(requestBody.toJSONString(), JSON_MEDIA_TYPE))
                    .build();

            try (Response response = execute(request)) {
                String body = response.body() != null ? response.body().string() : "{}";
                if (!response.isSuccessful()) {
                    // HTTP 非 2xx：降级原序（不抛出）
                    log.warn("上游 rerank API 调用失败(HTTP {}): 降级原序返回", response.code());
                    return passThrough(candidates, topK);
                }
                return applyRerankResults(body, candidates, topK);
            }
        } catch (Exception e) {
            // 超时 / 网络 / 解析异常：降级原序（端口契约：不抛出）
            log.warn("上游 rerank 异常({}), 降级原序返回: {}", e.getClass().getSimpleName(), e.getMessage());
            return passThrough(candidates, topK);
        }
    }

    @Override
    public boolean isAvailable() {
        // 上游适配以 API Key 配置就绪为可用条件
        return rerankApiKey != null && !rerankApiKey.isBlank();
    }

    /** 提取 execute 便于单测 mock Call 链路（含超时/IO 异常注入） */
    Response execute(Request request) throws IOException {
        Call call = httpClient.newCall(request);
        return call.execute();
    }

    /** 解析响应并按 relevance_score 降序重建结果；解析失败抛出由外层降级 */
    private List<VectorSearchResultVO> applyRerankResults(String body, List<VectorSearchResultVO> candidates, int topK) {
        JSONObject jsonResponse = JSON.parseObject(body);
        JSONArray resultsArray = jsonResponse.getJSONArray("results");
        if (resultsArray == null || resultsArray.isEmpty()) {
            log.warn("上游 rerank 响应无 results，降级原序返回");
            return passThrough(candidates, topK);
        }

        List<VectorSearchResultVO> reranked = new ArrayList<>();
        for (int i = 0; i < resultsArray.size(); i++) {
            JSONObject item = resultsArray.getJSONObject(i);
            int index = item.getIntValue("index");
            double relevanceScore = item.getDoubleValue("relevance_score");
            if (index >= 0 && index < candidates.size()) {
                VectorSearchResultVO original = candidates.get(index);
                reranked.add(VectorSearchResultVO.builder()
                        .content(original.getContent())
                        .score((float) relevanceScore)
                        .metadata(original.getMetadata())
                        .build());
            }
        }
        return reranked.size() > topK ? new ArrayList<>(reranked.subList(0, topK)) : reranked;
    }

    /** 规则降级：原分数原序，仅截断 topK（与 PassThroughReranker 同口径） */
    private List<VectorSearchResultVO> passThrough(List<VectorSearchResultVO> candidates, int topK) {
        return candidates.stream()
                .limit(Math.max(topK, 0))
                .collect(java.util.stream.Collectors.toList());
    }
}
