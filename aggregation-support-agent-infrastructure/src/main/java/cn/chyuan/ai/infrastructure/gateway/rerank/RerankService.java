package cn.chyuan.ai.infrastructure.gateway.rerank;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.rerank.IRerankService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Rerank重排序服务实现 — 调用外部Rerank API进行精排
 * <p>
 * 支持的Rerank服务：
 * <ul>
 *   <li>Cohere Rerank</li>
 *   <li>Jina Reranker</li>
 *   <li>自部署BGE-Reranker</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rag.rerank.enabled", havingValue = "true", matchIfMissing = false)
public class RerankService implements IRerankService {

    @Value("${rag.rerank.api-url}")
    private String rerankApiUrl;

    @Value("${rag.rerank.api-key}")
    private String rerankApiKey;

    @Value("${rag.rerank.model}")
    private String rerankModel;

    @Value("${rag.rerank.timeout}")
    private int timeout;

    @Resource
    private OkHttpClient httpClient;

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    @Override
    public List<VectorSearchResultVO> rerank(String query, List<VectorSearchResultVO> candidates, int topK) {
        log.info("Rerank重排序: query={}, candidateCount={}, topK={}", query, candidates.size(), topK);

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 如果候选数量小于等于topK，直接返回
        if (candidates.size() <= topK) {
            log.info("候选数量小于topK，跳过Rerank");
            return candidates;
        }

        try {
            // 调用Rerank API
            RerankResponse response = callRerankApi(query, candidates, topK);

            // 根据Rerank结果重新排序
            List<VectorSearchResultVO> rerankedResults = new ArrayList<>();
            for (RerankResult result : response.getResults()) {
                int index = result.getIndex();
                if (index >= 0 && index < candidates.size()) {
                    VectorSearchResultVO originalChunk = candidates.get(index);
                    // 使用Rerank分数
                    rerankedResults.add(VectorSearchResultVO.builder()
                            .content(originalChunk.getContent())
                            .score((float) result.getRelevanceScore())
                            .metadata(originalChunk.getMetadata())
                            .build());
                }
            }

            log.info("Rerank完成: resultCount={}", rerankedResults.size());
            return rerankedResults;

        } catch (Exception e) {
            log.error("Rerank失败，返回原始排序: {}", e.getMessage());
            // 降级：返回原始排序的前topK个
            return candidates.stream().limit(topK).collect(Collectors.toList());
        }
    }

    @Override
    public boolean isAvailable() {
        return rerankApiKey != null && !rerankApiKey.isEmpty();
    }

    /**
     * 调用Rerank API
     */
    private RerankResponse callRerankApi(String query, List<VectorSearchResultVO> candidates, int topK) throws IOException {
        // 构建请求体
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", rerankModel);
        requestBody.put("query", query);
        requestBody.put("top_n", topK);

        // 构建文档列表
        JSONArray documents = new JSONArray();
        for (VectorSearchResultVO candidate : candidates) {
            documents.add(candidate.getContent());
        }
        requestBody.put("documents", documents);

        // 构建HTTP请求
        Request request = new Request.Builder()
                .url(rerankApiUrl)
                .addHeader("Authorization", "Bearer " + rerankApiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toJSONString(), JSON_MEDIA_TYPE))
                .build();

        // 发送请求
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorMsg = response.body() != null ? response.body().string() : "unknown error";
                log.error("Rerank API调用失败: status={}, body={}", response.code(), errorMsg);
                throw new RuntimeException("Rerank API调用失败: HTTP " + response.code());
            }

            String responseBody = response.body() != null ? response.body().string() : "{}";
            return parseRerankResponse(responseBody);
        }
    }

    /**
     * 解析Rerank API响应
     */
    private RerankResponse parseRerankResponse(String responseBody) {
        JSONObject jsonResponse = JSON.parseObject(responseBody);
        JSONArray resultsArray = jsonResponse.getJSONArray("results");

        RerankResponse rerankResponse = new RerankResponse();
        List<RerankResult> results = new ArrayList<>();

        for (int i = 0; i < resultsArray.size(); i++) {
            JSONObject resultObj = resultsArray.getJSONObject(i);
            RerankResult result = new RerankResult();
            result.setIndex(resultObj.getIntValue("index"));
            result.setRelevanceScore(resultObj.getDoubleValue("relevance_score"));
            results.add(result);
        }

        rerankResponse.setResults(results);
        return rerankResponse;
    }

    /**
     * Rerank响应
     */
    @lombok.Data
    private static class RerankResponse {
        private List<RerankResult> results;
    }

    /**
     * Rerank结果
     */
    @lombok.Data
    private static class RerankResult {
        private int index;
        private double relevanceScore;
    }

}
