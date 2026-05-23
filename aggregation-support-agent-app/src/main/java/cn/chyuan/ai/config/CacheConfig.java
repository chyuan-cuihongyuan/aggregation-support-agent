package cn.chyuan.ai.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 缓存配置
 * 支持Redis和Caffeine两级缓存，Redis不可用时自动降级到Caffeine本地缓存
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Caffeine本地缓存配置（主要缓存方案）
     */
    @Bean
    @Primary
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .recordStats());
        log.info("Caffeine缓存管理器初始化完成");
        return cacheManager;
    }

    /**
     * Redis缓存配置（可选，如果Redis可用）
     */
    @Bean
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        try {
            // 配置序列化
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
            objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
            );

            GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

            // 默认缓存配置
            RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                    .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                    .fromSerializer(serializer))
                .disableCachingNullValues();

            // 针对不同业务场景的缓存配置
            Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

            // 用户缓存：1小时过期
            cacheConfigurations.put("user", defaultConfig.entryTtl(Duration.ofHours(1)));

            // 知识库缓存：2小时过期
            cacheConfigurations.put("knowledge", defaultConfig.entryTtl(Duration.ofHours(2)));

            // 文档缓存：30分钟过期
            cacheConfigurations.put("document", defaultConfig.entryTtl(Duration.ofMinutes(30)));

            // 插件缓存：24小时过期
            cacheConfigurations.put("plugin", defaultConfig.entryTtl(Duration.ofHours(24)));

            RedisCacheManager cacheManager = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware()
                .build();

            log.info("Redis缓存管理器初始化完成");
            return cacheManager;
        } catch (Exception e) {
            log.warn("Redis不可用，将使用Caffeine本地缓存: {}", e.getMessage());
            return null;
        }
    }
}