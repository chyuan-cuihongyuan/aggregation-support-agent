package cn.chyuan.ai.infrastructure.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.Neo4jClient;

/**
 * Neo4j 配置
 */
@Data
@Configuration
@ConditionalOnProperty(name = "neo4j.enabled", havingValue = "true")
@ConfigurationProperties(prefix = "neo4j")
public class Neo4jConfig {

    private String uri = "bolt://49.232.169.33:7687";
    private Authentication authentication = new Authentication();

    @Data
    public static class Authentication {
        private String username = "neo4j";
        private String password = "chy010731";
    }

    @Bean
    public org.neo4j.driver.Driver neo4jDriver() {
        return org.neo4j.driver.GraphDatabase.driver(
                uri,
                org.neo4j.driver.AuthTokens.basic(authentication.getUsername(), authentication.getPassword())
        );
    }

    @Bean
    public Neo4jClient neo4jClient(org.neo4j.driver.Driver driver) {
        return Neo4jClient.create(driver);
    }
}
