package cn.chyuan.ai.infrastructure.gateway.cache;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 二级嵌入向量缓存服务 — L1 本地 Guava + L2 Redis
 * <p>
 * 缓存策略：Write-Through（写穿透）
 * <ul>
 *   <li>读取：L1 → L2 → delegate，命中任一层级即返回</li>
 *   <li>写入：同时写入 L1 和 L2，保证一致性</li>
 * </ul>
 * <p>
 * 跨实例共享：多个实例共享同一 Redis 缓存，热点 query 零延迟。
 * 模型切换安全：cache key 包含 modelName，模型变更后自动失效。
 */
@Slf4j
public class TwoLevelEmbeddingCacheService implements IEmbeddingService {

    private final IEmbeddingService delegate;
    private final String modelName;
    private final RedisTemplate<String, String> redisTemplate;
    private final long redisTtlHours;

    /** L1: 本地 Guava Cache */
    private final Cache<String, float[]> l1Cache;

    /** 缓存 key 前缀 */
    private static final String REDIS_KEY_PREFIX = "rag:embed:";

    /** 统计计数器 */
    private final AtomicLong l1HitCount = new AtomicLong(0);
    private final AtomicLong l2HitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);

    public TwoLevelEmbeddingCacheService(
            IEmbeddingService delegate,
            int localMaxSize,
            int localExpireHours,
            String modelName,
            RedisTemplate<String, String> redisTemplate,
            long redisTtlHours,
            MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.modelName = modelName == null || modelName.isBlank() ? "default" : modelName;
        this.redisTemplate = redisTemplate;
        this.redisTtlHours = redisTtlHours;

        this.l1Cache = CacheBuilder.newBuilder()
                .maximumSize(localMaxSize)
                .expireAfterAccess(localExpireHours, TimeUnit.HOURS)
                .recordStats()
                .build();

        // 注册 Micrometer 指标
        if (meterRegistry != null) {
            String modelTag = this.modelName;

            Gauge.builder("rag_embed_l1_size", l1Cache, Cache::size)
                    .tag("model", modelTag)
                    .description("L1 本地缓存大小")
                    .register(meterRegistry);

            Gauge.builder("rag_embed_l1_hit_total", l1HitCount, AtomicLong::get)
                    .tag("model", modelTag)
                    .description("L1 缓存命中次数")
                    .register(meterRegistry);

            Gauge.builder("rag_embed_l2_hit_total", l2HitCount, AtomicLong::get)
                    .tag("model", modelTag)
                    .description("L2 Redis 缓存命中次数")
                    .register(meterRegistry);

            Gauge.builder("rag_embed_miss_total", missCount, AtomicLong::get)
                    .tag("model", modelTag)
                    .description("缓存未命中次数（需调用 API）")
                    .register(meterRegistry);
        }

        log.info("二级嵌入缓存初始化: L1 maxSize={}, expireHours={}h, L2 redisTtlHours={}h, modelName={}",
                localMaxSize, localExpireHours, redisTtlHours, this.modelName);
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new float[0];
        }

        String cacheKey = generateCacheKey(text);

        // L1: 本地缓存
        float[] result = l1Cache.getIfPresent(cacheKey);
        if (result != null) {
            l1HitCount.incrementAndGet();
            log.debug("L1 缓存命中: key={}", shortKey(cacheKey));
            return result;
        }

        // L2: Redis 缓存
        result = loadFromRedis(cacheKey);
        if (result != null) {
            l2HitCount.incrementAndGet();
            l1Cache.put(cacheKey, result); // 回填 L1
            log.debug("L2 Redis 缓存命中: key={}", shortKey(cacheKey));
            return result;
        }

        // 均未命中，调用实际服务
        missCount.incrementAndGet();
        result = delegate.embed(text);

        // Write-Through: 同时写入 L1 和 L2
        if (result != null && result.length > 0) {
            l1Cache.put(cacheKey, result);
            saveToRedis(cacheKey, result);
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

        // 1. 批量查询 L1 + L2
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.trim().isEmpty()) {
                results.add(new float[0]);
                continue;
            }

            String cacheKey = generateCacheKey(text);

            // L1
            float[] cached = l1Cache.getIfPresent(cacheKey);
            if (cached != null) {
                l1HitCount.incrementAndGet();
                results.add(cached);
                continue;
            }

            // L2
            cached = loadFromRedis(cacheKey);
            if (cached != null) {
                l2HitCount.incrementAndGet();
                l1Cache.put(cacheKey, cached);
                results.add(cached);
                continue;
            }

            // 均未命中
            missCount.incrementAndGet();
            results.add(null);
            uncachedIndices.add(i);
        }

        // 2. 对未缓存的文本批量调用嵌入服务
        if (!uncachedIndices.isEmpty()) {
            log.info("批量嵌入: 总数={}, L1命中={}, L2命中={}, 需调用API={}",
                    texts.size(),
                    texts.size() - uncachedIndices.size() - (int) texts.stream().filter(t -> t == null || t.trim().isEmpty()).count(),
                    0, // L2 命中已在上面循环中统计
                    uncachedIndices.size());

            List<String> uncachedTexts = uncachedIndices.stream()
                    .map(texts::get)
                    .toList();

            List<float[]> apiResults = delegate.embedBatch(uncachedTexts);

            // Write-Through: 同时写入 L1 和 L2
            for (int i = 0; i < uncachedIndices.size(); i++) {
                int originalIndex = uncachedIndices.get(i);
                float[] result = apiResults.get(i);

                if (result != null && result.length > 0) {
                    String cacheKey = generateCacheKey(uncachedTexts.get(i));
                    l1Cache.put(cacheKey, result);
                    saveToRedis(cacheKey, result);
                }

                results.set(originalIndex, result);
            }
        }

        return results;
    }

    // ==================== Redis 操作 ====================

    /**
     * 从 Redis 加载缓存
     */
    private float[] loadFromRedis(String cacheKey) {
        try {
            String redisKey = REDIS_KEY_PREFIX + cacheKey;
            String encoded = redisTemplate.opsForValue().get(redisKey);
            if (encoded != null && !encoded.isEmpty()) {
                return deserializeVector(encoded);
            }
        } catch (Exception e) {
            log.warn("Redis 缓存读取失败，降级为未命中: key={}, error={}", shortKey(cacheKey), e.getMessage());
        }
        return null;
    }

    /**
     * 写入 Redis 缓存
     */
    private void saveToRedis(String cacheKey, float[] vector) {
        try {
            String redisKey = REDIS_KEY_PREFIX + cacheKey;
            String encoded = serializeVector(vector);
            redisTemplate.opsForValue().set(redisKey, encoded, redisTtlHours, TimeUnit.HOURS);
            log.debug("已写入 Redis 缓存: key={}", shortKey(cacheKey));
        } catch (Exception e) {
            log.warn("Redis 缓存写入失败，忽略: key={}, error={}", shortKey(cacheKey), e.getMessage());
        }
    }

    // ==================== 序列化 ====================

    /**
     * float[] → Base64 字符串
     */
    private static String serializeVector(float[] vector) {
        ByteBuffer buf = ByteBuffer.allocate(vector.length * 4);
        for (float f : vector) {
            buf.putFloat(f);
        }
        return Base64.getEncoder().encodeToString(buf.array());
    }

    /**
     * Base64 字符串 → float[]
     */
    private static float[] deserializeVector(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / 4];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buf.getFloat();
        }
        return vector;
    }

    // ==================== 工具方法 ====================

    /**
     * 生成缓存 key（文本归一化后的 MD5 hash）
     */
    private String generateCacheKey(String text) {
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
            return "embedding:" + modelName + ":" + normalized.hashCode();
        }
    }

    private static String shortKey(String key) {
        return key.substring(0, Math.min(16, key.length())) + "...";
    }

    // ==================== 管理接口 ====================

    /**
     * 清空所有缓存（L1 + L2）
     */
    public void clearCache() {
        l1Cache.invalidateAll();
        try {
            // 清空 Redis 中本模型的所有嵌入缓存
            java.util.Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX + "embedding:" + modelName + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("已清空 Redis 嵌入缓存: model={}, count={}", modelName, keys.size());
            }
        } catch (Exception e) {
            log.warn("清空 Redis 缓存失败: {}", e.getMessage());
        }
        log.info("二级嵌入缓存已清空: model={}", modelName);
    }

    /**
     * 获取缓存统计信息
     */
    public TwoLevelCacheStats getStats() {
        long l1Hits = l1HitCount.get();
        long l2Hits = l2HitCount.get();
        long misses = missCount.get();
        long total = l1Hits + l2Hits + misses;

        return TwoLevelCacheStats.builder()
                .l1Size(l1Cache.size())
                .l1HitCount(l1Hits)
                .l2HitCount(l2Hits)
                .missCount(misses)
                .l1HitRate(total > 0 ? (double) l1Hits / total : 0)
                .l2HitRate(total > 0 ? (double) l2Hits / total : 0)
                .overallHitRate(total > 0 ? (double) (l1Hits + l2Hits) / total : 0)
                .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class TwoLevelCacheStats {
        private long l1Size;
        private long l1HitCount;
        private long l2HitCount;
        private long missCount;
        private double l1HitRate;
        private double l2HitRate;
        private double overallHitRate;

        @Override
        public String toString() {
            return String.format("TwoLevelCacheStats{L1=%d/%.1f%%, L2=%d/%.1f%%, miss=%d, overall=%.1f%%}",
                    l1HitCount, l1HitRate * 100,
                    l2HitCount, l2HitRate * 100,
                    missCount, overallHitRate * 100);
        }
    }
}
