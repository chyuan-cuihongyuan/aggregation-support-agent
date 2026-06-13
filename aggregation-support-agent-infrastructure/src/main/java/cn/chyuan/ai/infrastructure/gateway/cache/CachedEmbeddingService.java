package cn.chyuan.ai.infrastructure.gateway.cache;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

    private final String modelName;

    /** 缓存实例 */
    private final Cache<String, float[]> cache;

    /** 缓存命中次数（唯一计数源，Micrometer 通过 Gauge 绑定读取） */
    private final AtomicLong hitCount = new AtomicLong(0);

    /** 缓存未命中次数（唯一计数源，Micrometer 通过 Gauge 绑定读取） */
    private final AtomicLong missCount = new AtomicLong(0);

    public CachedEmbeddingService(
            IEmbeddingService delegate,
            int maxSize,
            int expireHours,
            String modelName,
            MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.modelName = modelName == null || modelName.isBlank() ? "default" : modelName;
        this.cache = CacheBuilder.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(expireHours, TimeUnit.HOURS)
                .recordStats()
                .build();

        // Micrometer 指标统一通过 Gauge 绑定到 AtomicLong，单一计数源，避免双重计数语义不一致
        if (meterRegistry != null) {
            Gauge.builder("rag_embedding_cache_size", cache, c -> c.size())
                    .tag("model", this.modelName)
                    .description("当前缓存大小")
                    .register(meterRegistry);
            Gauge.builder("rag_embedding_cache_hit_total", hitCount, AtomicLong::get)
                    .tag("model", this.modelName)
                    .description("缓存命中次数")
                    .register(meterRegistry);
            Gauge.builder("rag_embedding_cache_miss_total", missCount, AtomicLong::get)
                    .tag("model", this.modelName)
                    .description("缓存未命中次数")
                    .register(meterRegistry);
        }

        log.info("嵌入向量缓存初始化: maxSize={}, expireHours={}, modelName={}", maxSize, expireHours, this.modelName);
    }

    @Override
    public int dimension() {
        // 透传底层提供商维度，供降级链的维度兼容校验使用
        return delegate.dimension();
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
            hitCount.incrementAndGet();
            log.debug("嵌入缓存命中: key={}", cacheKey.substring(0, Math.min(8, cacheKey.length())));
            return cached;
        }

        // 缓存未命中，调用实际服务
        missCount.incrementAndGet();
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
                hitCount.incrementAndGet();
                results.add(cached);
            } else {
                missCount.incrementAndGet();
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
     * 生成缓存key（文本归一化后的 MD5 hash）
     * <p>
     * 归一化处理：trim + 小写 + 统一空白，使仅有大小写/空白差异的相同 query 能命中同一缓存条目。
     */
    private String generateCacheKey(String text) {
        // 归一化：去除首尾空白、转小写、合并连续空白为单个空格
        String normalized = text.trim().toLowerCase().replaceAll("\\s+", " ");
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return "embedding:" + modelName + ":" + sb;
        } catch (NoSuchAlgorithmException e) {
            // 降级：使用归一化文本的 hashcode
            return "embedding:" + modelName + ":" + normalized.hashCode();
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
        long hits = hitCount.get();
        long misses = missCount.get();
        return CacheStats.builder()
                .size(cache.size())
                .hitCount(hits)
                .missCount(misses)
                .hitRate(hits + misses > 0 ? (double) hits / (hits + misses) : 0)
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
