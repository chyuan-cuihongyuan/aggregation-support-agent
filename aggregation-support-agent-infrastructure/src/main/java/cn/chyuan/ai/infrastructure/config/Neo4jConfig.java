package cn.chyuan.ai.infrastructure.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Neo4j 配置
 * <p>
 * 连接地址与凭据全部经 Spring 配置属性/环境变量注入（安全扫描整改 0016），
 * 源码不携带任何默认值；neo4j.enabled=true 且必填配置缺失时启动快速失败，
 * 报出缺失的配置项名，避免带空凭据继续运行。
 */
@Slf4j
@Data
@Configuration
@ConditionalOnProperty(name = "neo4j.enabled", havingValue = "true")
@ConfigurationProperties(prefix = "neo4j")
public class Neo4jConfig {

    private String uri;

    // ===== 连接生命周期（SELFLOOP2 loop-246）：安全默认，可经同名属性外置 =====
    /** TCP 连接建立超时（秒） */
    private int connectionTimeoutSeconds = 10;
    /** 从池获取连接的超时（秒） */
    private int connectionAcquisitionTimeoutSeconds = 30;
    /** 连接池上限 */
    private int maxConnectionPoolSize = 50;
    /** 连接最长寿命（秒），到期由池回收重建 */
    private long maxConnectionLifetimeSeconds = 3600;

    private Authentication authentication = new Authentication();

    @Data
    public static class Authentication {
        private String username;
        private String password;
    }

    @Bean
    public org.neo4j.driver.Driver neo4jDriver() {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(uri)) {
            missing.add("neo4j.uri");
        }
        String username = authentication == null ? null : authentication.getUsername();
        String password = authentication == null ? null : authentication.getPassword();
        if (!StringUtils.hasText(username)) {
            missing.add("neo4j.authentication.username");
        }
        if (!StringUtils.hasText(password)) {
            missing.add("neo4j.authentication.password");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Neo4j 已启用但缺少必要配置，启动快速失败，缺失配置项: "
                    + String.join(", ", missing));
        }
        org.neo4j.driver.Config config = org.neo4j.driver.Config.builder()
                .withConnectionTimeout(connectionTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .withConnectionAcquisitionTimeout(connectionAcquisitionTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .withMaxConnectionPoolSize(maxConnectionPoolSize)
                .withMaxConnectionLifetime(maxConnectionLifetimeSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        return org.neo4j.driver.GraphDatabase.driver(
                uri,
                org.neo4j.driver.AuthTokens.basic(authentication.getUsername(), authentication.getPassword()),
                config
        );
    }

    @Bean
    public Neo4jClient neo4jClient(org.neo4j.driver.Driver driver) {
        return Neo4jClient.create(driver);
    }
}