package cn.chyuan.ai.domain.rag.service.cache;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 检索结果缓存服务（工单 0167，W5）— 归一化键 + LRU/TTL 纯内核
 * <p>
 * 缓存口径：仅缓存检索 chunks+分数（不缓存生成内容）。
 * <ul>
 *   <li>键生成：query 归一化（小写 → 去所有空白 → 分词去停用词（复用 W3 停用词表））
 *       → SHA-256；等价 query 同键</li>
 *   <li>淘汰：TTL（默认 300s，可配）+ LRU 容量上限（访问序）</li>
 *   <li>Clock 注入可测；hit/miss 计数供 Micrometer Gauge 绑定
 *       （retrieval_cache_hit_total / retrieval_cache_miss_total）</li>
 * </ul>
 * domain 纯实现：零框架依赖；线程安全（synchronized 内 LinkedHashMap accessOrder）。
 */
public class RetrievalCacheService {

    /** 默认 TTL（秒） */
    public static final long DEFAULT_TTL_SECONDS = 300;
    /** 默认容量上限 */
    public static final int DEFAULT_MAX_CAPACITY = 1000;

    /** 分词口径与 W3 规则改写一致：保留小写字母/数字/中文，其余作为分隔（含全部空白） */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9\\u4e00-\\u9fa5]+");
    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]");

    private final int maxCapacity;
    private final long ttlMillis;
    private final java.time.Clock clock;
    private final Set<String> stopwords;

    /** LRU 缓存（accessOrder=true，get/put 均更新访问序） */
    private final LinkedHashMap<String, CacheEntry> cache;

    /** 命中/未命中计数（Micrometer Gauge 绑定的唯一计数源） */
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

    private static final class CacheEntry {
        final List<VectorSearchResultVO> results;
        final long storedAtMillis;

        CacheEntry(List<VectorSearchResultVO> results, long storedAtMillis) {
            this.results = results;
            this.storedAtMillis = storedAtMillis;
        }
    }

    public RetrievalCacheService(int maxCapacity, long ttlSeconds, java.time.Clock clock, Set<String> stopwords) {
        this.maxCapacity = maxCapacity > 0 ? maxCapacity : DEFAULT_MAX_CAPACITY;
        this.ttlMillis = ttlSeconds > 0 ? ttlSeconds * 1000L : DEFAULT_TTL_SECONDS * 1000L;
        this.clock = clock != null ? clock : java.time.Clock.systemUTC();
        this.stopwords = stopwords != null ? stopwords : Collections.emptySet();
        this.cache = new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * 查询缓存
     *
     * @param query 原始 query
     * @return 命中返回缓存结果；未命中/过期返回 empty（并累计 miss）
     */
    public Optional<List<VectorSearchResultVO>> get(String query) {
        String key = buildKey(query);
        synchronized (this) {
            CacheEntry entry = cache.get(key);
            if (entry == null) {
                missCount.incrementAndGet();
                return Optional.empty();
            }
            if (clock.millis() - entry.storedAtMillis > ttlMillis) {
                // TTL 过期：移除并计 miss
                cache.remove(key);
                missCount.incrementAndGet();
                return Optional.empty();
            }
            hitCount.incrementAndGet();
            return Optional.of(entry.results);
        }
    }

    /**
     * 写入缓存（LRU 淘汰超容量最久未访问条目）
     */
    public void put(String query, List<VectorSearchResultVO> results) {
        if (query == null || results == null) {
            return;
        }
        String key = buildKey(query);
        synchronized (this) {
            cache.put(key, new CacheEntry(results, clock.millis()));
            // 容量淘汰：移除最久未访问（LinkedHashMap 迭代序头元素）
            while (cache.size() > maxCapacity) {
                Map.Entry<String, CacheEntry> eldest = cache.entrySet().iterator().next();
                cache.remove(eldest.getKey());
            }
        }
    }

    /**
     * query 归一化：小写 → 分词去停用词 → 无分隔重组
     * <p>
     * 等价 query 归一化结果相同（如 "Hello World" ≡ "hello world" ≡ "h e l l o world"）。
     */
    public String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        String lower = query.toLowerCase();
        StringBuilder normalized = new StringBuilder();
        for (String segment : TOKEN_SPLIT.split(lower)) {
            if (segment.isEmpty()) {
                continue;
            }
            if (CHINESE.matcher(segment).find()) {
                // 中文段：整段停用词过滤 + 逐字停用字过滤（与 W3 表口径一致）
                if (stopwords.contains(segment)) {
                    continue;
                }
                for (int i = 0; i < segment.length(); i++) {
                    String ch = String.valueOf(segment.charAt(i));
                    if (!stopwords.contains(ch)) {
                        normalized.append(ch);
                    }
                }
            } else if (!stopwords.contains(segment)) {
                normalized.append(segment);
            }
        }
        return normalized.toString();
    }

    /**
     * 生成缓存键：SHA-256(归一化 query)
     */
    public String buildKey(String query) {
        return sha256(normalizeQuery(query));
    }

    /** 缓存命中次数（供 Micrometer Gauge 绑定：retrieval_cache_hit_total） */
    public long hitCount() {
        return hitCount.get();
    }

    /** 缓存未命中次数（供 Micrometer Gauge 绑定：retrieval_cache_miss_total） */
    public long missCount() {
        return missCount.get();
    }

    /** 当前缓存条目数（供 Gauge/观测） */
    public synchronized int size() {
        return cache.size();
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // 极端不可用回退，保持键函数可用
            return "len" + text.length() + "_" + text.hashCode();
        }
    }
}
