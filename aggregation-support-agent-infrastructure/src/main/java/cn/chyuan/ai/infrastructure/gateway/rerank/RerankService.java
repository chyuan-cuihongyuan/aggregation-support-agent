package cn.chyuan.ai.infrastructure.gateway.rerank;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.rerank.IRerankService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Rerank重排序服务实现 — 调用外部Rerank API进行精排
 * <p>
 * 支持的Rerank服务：
 * <ul>
 *   <li>智谱 BigModel Rerank（默认）</li>
 *   <li>Cohere Rerank</li>
 *   <li>Jina Reranker</li>
 *   <li>自部署BGE-Reranker</li>
 * </ul>
 * <p>
 * 优化特性：
 * <ul>
 *   <li>本地缓存：相同 query+documents 组合直接命中缓存，减少 API 调用</li>
 *   <li>降级策略：API 不可用时按原始相似度分数排序，而非简单截断</li>
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

    /** Rerank 结果缓存（query+documents hash → 排序后的结果） */
    private final Cache<String, List<VectorSearchResultVO>> rerankCache = CacheBuilder.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();

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

        // 1. 尝试从缓存获取（key 含 topK，避免不同 topK 复用被截断的旧结果）
        String cacheKey = generateRerankCacheKey(query, candidates, topK);
        List<VectorSearchResultVO> cachedResult = rerankCache.getIfPresent(cacheKey);
        if (cachedResult != null) {
            log.info("Rerank缓存命中: query={}, resultCount={}", query, cachedResult.size());
            return cachedResult.stream().limit(topK).collect(Collectors.toList());
        }

        try {
            // 2. 调用Rerank API
            RerankResponse response = callRerankApi(query, candidates, topK);

            // 3. 根据Rerank结果重新排序
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

            // 4. 存入缓存
            rerankCache.put(cacheKey, rerankedResults);

            log.info("Rerank完成: resultCount={}", rerankedResults.size());
            return rerankedResults;

        } catch (Exception e) {
            log.error("Rerank失败，保留既有相关度顺序降级: {}", e.getMessage());
            // 降级策略：candidates 传入时已是"最相关在前"的有序结果
            // （向量检索按 L2 距离升序、RRF 融合按分数降序），直接取前 topK 即保持正确相关度顺序。
            // 不能按 score 字段重排：不同来源 score 语义不同（L2 距离越小越相似 vs RRF 分数越大越相似）。
            return candidates.stream()
                    .limit(topK)
                    .collect(Collectors.toList());
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

        // 发送请求（只读取一次 body，避免 OkHttp body().string() 双读问题）
        // per-call 超时（T46）：rag.rerank.timeout 此前为死配置（未挂接），实际走共享 client 的 600s read
        okhttp3.Call call = httpClient.newCall(request);
        // per-call 超时设置（okio.Timeout 原地修改，返回 Timeout 非 Call，需拆行）
        call.timeout().timeout(timeout, TimeUnit.SECONDS);
        try (Response response = call.execute()) {
            // 提前读取 body 字符串，后续错误分支和成功分支共用
            String responseBody = response.body() != null ? response.body().string() : "{}";

            if (!response.isSuccessful()) {
                log.error("Rerank API调用失败: status={}, body={}", response.code(), responseBody);
                throw new RuntimeException("Rerank API调用失败: HTTP " + response.code());
            }

            return parseRerankResponse(responseBody);
        }
    }

    /**
     * 生成 Rerank 缓存 key（基于 query + topK + 所有候选文档内容的 hash）
     */
    private String generateRerankCacheKey(String query, List<VectorSearchResultVO> candidates, int topK) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(query.getBytes(StandardCharsets.UTF_8));
            md.update(("|topK=" + topK + "|").getBytes(StandardCharsets.UTF_8));
            for (VectorSearchResultVO candidate : candidates) {
                md.update(candidate.getContent().getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 降级：使用 hashCode
            return query.hashCode() + "_" + topK + "_" + candidates.hashCode();
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
