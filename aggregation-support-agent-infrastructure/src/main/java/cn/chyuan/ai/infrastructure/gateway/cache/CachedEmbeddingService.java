package cn.chyuan.ai.infrastructure.gateway.cache;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 嵌入向量缓存服务 — 缓存嵌入结果，避免重复调用嵌入API
 * <p>
 * 使用Guava Cache实现本地缓存，按文本hash索引
 * <p>
 * 优化点：
 * <ul>
 *   <li>减少嵌入API调用次数，降低成本</li>
 *   <li>提高响应速度（缓存命中时）</li>
 *   <li>支持批量查询优化</li>
 * </ul>
 */
@Slf4j
public class CachedEmbeddingService implements IEmbeddingService {

    private final IEmbeddingService delegate;

    /** 缓存实例 */
    private final Cache<String, float[]> cache;

    /** 缓存命中次数 */
    private long hitCount = 0;

    /** 缓存未命中次数 */
    private long missCount = 0;

    public CachedEmbeddingService(
            IEmbeddingService delegate,
            @Value("${rag.cache.embedding.max-size:10000}") int maxSize,
            @Value("${rag.cache.embedding.expire-hours:24}") int expireHours) {
        this.delegate = delegate;
        this.cache = CacheBuilder.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(expireHours, TimeUnit.HOURS)
                .recordStats()
                .build();

        log.info("嵌入向量缓存初始化: maxSize={}, expireHours={}", maxSize, expireHours);
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new float[0];
        }

        String cacheKey = generateCacheKey(text);

        // 尝试从缓存获取
        float[] cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            hitCount++;
            log.debug("嵌入缓存命中: key={}", cacheKey.substring(0, Math.min(8, cacheKey.length())));
            return cached;
        }

        // 缓存未命中，调用实际服务
        missCount++;
        float[] result = delegate.embed(text);

        // 存入缓存
        if (result != null && result.length > 0) {
            cache.put(cacheKey, result);
            log.debug("嵌入结果已缓存: key={}", cacheKey.substring(0, Math.min(8, cacheKey.length())));
        }

        return result;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        List<float[]> results = new ArrayList<>(texts.size());
        List<Integer> uncachedIndices = new ArrayList<>();
        List<String> uncachedTexts = new ArrayList<>();

        // 1. 先从缓存中批量查询
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.trim().isEmpty()) {
                results.add(new float[0]);
                continue;
            }

            String cacheKey = generateCacheKey(text);
            float[] cached = cache.getIfPresent(cacheKey);

            if (cached != null) {
                hitCount++;
                results.add(cached);
            } else {
                missCount++;
                results.add(null); // 占位
                uncachedIndices.add(i);
                uncachedTexts.add(text);
            }
        }

        // 2. 对未缓存的文本批量调用嵌入服务
        if (!uncachedTexts.isEmpty()) {
            log.info("批量嵌入: 总数={}, 缓存命中={}, 需要调用={}",
                    texts.size(), texts.size() - uncachedTexts.size(), uncachedTexts.size());

            List<float[]> uncachedResults = delegate.embedBatch(uncachedTexts);

            // 3. 将结果存入缓存并填充到结果列表
            for (int i = 0; i < uncachedIndices.size(); i++) {
                int originalIndex = uncachedIndices.get(i);
                float[] result = uncachedResults.get(i);

                if (result != null && result.length > 0) {
                    String cacheKey = generateCacheKey(uncachedTexts.get(i));
                    cache.put(cacheKey, result);
                }

                results.set(originalIndex, result);
            }
        }

        return results;
    }

    /**
     * 生成缓存key（文本的MD5 hash）
     */
    private String generateCacheKey(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // 降级：使用文本hashcode
            return String.valueOf(text.hashCode());
        }
    }

    /**
     * 清空缓存
     */
    public void clearCache() {
        cache.invalidateAll();
        log.info("嵌入向量缓存已清空");
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        com.google.common.cache.CacheStats guavaStats = cache.stats();
        return CacheStats.builder()
                .size(cache.size())
                .hitCount(hitCount)
                .missCount(missCount)
                .hitRate(hitCount + missCount > 0 ? (double) hitCount / (hitCount + missCount) : 0)
                .evictionCount(guavaStats.evictionCount())
                .build();
    }

    /**
     * 缓存统计信息
     */
    @lombok.Data
    @lombok.Builder
    public static class CacheStats {
        private long size;
        private long hitCount;
        private long missCount;
        private double hitRate;
        private long evictionCount;

        @Override
        public String toString() {
            return String.format("CacheStats{size=%d, hit=%d, miss=%d, hitRate=%.2f%%, evictions=%d}",
                    size, hitCount, missCount, hitRate * 100, evictionCount);
        }
    }

}
