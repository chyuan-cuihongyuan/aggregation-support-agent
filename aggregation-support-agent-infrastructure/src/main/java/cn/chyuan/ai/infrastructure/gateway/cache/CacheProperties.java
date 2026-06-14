package cn.chyuan.ai.infrastructure.gateway.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 多级缓存配置属性。
 */
@ConfigurationProperties(prefix = "app.cache")
public class CacheProperties {

    private final CaffeineSpec caffeine = new CaffeineSpec();
    private final RedisSpec redis = new RedisSpec();

    public CaffeineSpec getCaffeine() { return caffeine; }
    public RedisSpec getRedis() { return redis; }

    public static class CaffeineSpec {
        /** L1 最大条目数，默认 1000 */
        private long maximumSize = 1000;
        /** L1 TTL，默认 5 分钟 */
        private Duration ttl = Duration.ofMinutes(5);

        public long getMaximumSize() { return maximumSize; }
        public void setMaximumSize(long maximumSize) { this.maximumSize = maximumSize; }
        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
    }

    public static class RedisSpec {
        /** key 前缀 */
        private String keyPrefix = "aggregation-support-agent:";
        /** L2 TTL，默认 10 分钟 */
        private Duration ttl = Duration.ofMinutes(10);

        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
    }
}
