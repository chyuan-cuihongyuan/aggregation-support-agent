package cn.chyuan.ai.infrastructure.gateway.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class MultiLevelCacheService {
    
    private final Cache<String, Object> l1Cache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build();
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    private final Counter l1HitCounter;
    private final Counter l2HitCounter;
    private final Counter missCounter;

    public MultiLevelCacheService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.l1HitCounter = Counter.builder("cache.l1.hit").register(meterRegistry);
        this.l2HitCounter = Counter.builder("cache.l2.hit").register(meterRegistry);
        this.missCounter = Counter.builder("cache.miss").register(meterRegistry);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> fallback) {
        // L1: Caffeine 本地缓存
        Object value = l1Cache.getIfPresent(key);
        if (value != null) {
            l1HitCounter.increment();
            return (T) value;
        }

        // L2: Redis 缓存
        value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            l2HitCounter.increment();
            l1Cache.put(key, value);
            return (T) value;
        }

        // L3: 源
        missCounter.increment();
        T result = fallback.get();
        if (result != null) {
            l1Cache.put(key, result);
            redisTemplate.opsForValue().set(key, result, Duration.ofMinutes(10));
        }
        return result;
    }

    public void put(String key, Object value) {
        l1Cache.put(key, value);
        redisTemplate.opsForValue().set(key, value, Duration.ofMinutes(10));
    }

    public void invalidate(String key) {
        l1Cache.invalidate(key);
        redisTemplate.delete(key);
    }
}
