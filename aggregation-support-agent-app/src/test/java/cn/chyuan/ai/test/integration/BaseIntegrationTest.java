package cn.chyuan.ai.test.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类
 * <p>
 * 使用 Testcontainers 启动 MySQL、Redis、Milvus 等依赖服务，
 * 所有集成测试类继承此类，自动获得测试环境。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseIntegrationTest {

    @Container
    protected static final MySQLContainer<?> mysqlContainer = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("test_db")
            .withUsername("test")
            .withPassword("test")
            .withEnv("MYSQL_ROOT_PASSWORD", "root")
            .withExposedPorts(3306)
            .waitingFor(Wait.forHealthcheck());

    @Container
    protected static final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // MySQL 配置
        registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl);
        registry.add("spring.datasource.username", mysqlContainer::getUsername);
        registry.add("spring.datasource.password", mysqlContainer::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

        // Redis 配置
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));

        // 禁用 Milvus（集成测试默认不使用向量数据库）
        registry.add("milvus.enabled", () -> "false");

        // 测试配置
        registry.add("test.integration.enabled", () -> "true");
    }

    @BeforeAll
    static void beforeAll() {
        // Testcontainers 会自动启动容器
        System.out.println("MySQL Container started: " + mysqlContainer.getJdbcUrl());
        System.out.println("Redis Container started: " + redisContainer.getHost() + ":" + redisContainer.getMappedPort(6379));
    }

    @AfterAll
    static void afterAll() {
        // Testcontainers 会自动停止容器
        System.out.println("Stopping Testcontainers...");
    }
}
