package cn.chyuan.ai.infrastructure.gateway.cache;

import cn.chyuan.ai.domain.rag.adapter.port.IQueryResultCache;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 查询结果缓存的 Guava 实现（infrastructure 层 adapter）。
 * <p>
 * 使用 Guava Cache 的 {@code cache.get(key, Callable)} 保证 get-then-put 原子性（computeIfAbsent 语义），
 * 解决 LinkedHashMap + synchronizedMap 的并发竞态（#3）。支持容量上限、TTL 过期、命中率统计。
 * 与 {@link CachedEmbeddingService} 保持一致的缓存技术选型。
 *
 * @author chyuan
 * @since 2026-06-13
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.cache.query-result.enabled", havingValue = "true", matchIfMissing = true)
public class GuavaQueryResultCache implements IQueryResultCache {

    private final Cache<String, List<VectorSearchResultVO>> cache = CacheBuilder.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .recordStats()
            .build();

    @Override
    public List<VectorSearchResultVO> getOrCompute(String key, Function<String, List<VectorSearchResultVO>> loader) {
        try {
            // Guava Cache.get(key, Callable) 原子 computeIfAbsent 语义，线程安全，避免并发重复计算（#3）
            return cache.get(key, () -> loader.apply(key));
        } catch (ExecutionException e) {
            // loader 抛异常时 Guava 包装为 ExecutionException，解包重新抛出原始异常
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("查询结果缓存加载失败: key=" + key, cause);
        }
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
        log.info("查询结果缓存已清空（文档变更触发失效）");
    }
}
