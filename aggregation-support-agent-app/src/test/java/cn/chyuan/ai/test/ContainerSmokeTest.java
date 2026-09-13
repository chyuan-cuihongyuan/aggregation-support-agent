package cn.chyuan.ai.test;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import redis.clients.jedis.Jedis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AUTOLOOP al-43 / 工单 1043：Testcontainers 试点冒烟（借鉴 testcontainers/testcontainers-java）。
 *
 * <p>试点锚点：MySQL + Redis 真容器连通样板（JDBC 建表插查 / Jedis 读写），
 * 供后续集成测试迁移复制。无 Docker 环境 assumeTrue 自跳过（CI ubuntu runner 自带 Docker 真跑）。
 * 刻意不拉 Spring 上下文：遗留 @SpringBootTest 与 LLM 配置耦合（工单 1037 §1），
 * 迁移为独立雾区主题。
 */
@Testcontainers
class ContainerSmokeTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse("mysql:8.0").asCompatibleSubstituteFor("mysql"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "info").start();
            boolean ok = p.waitFor() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void mysqlRoundtrip() throws Exception {
        assumeTrue(dockerAvailable(), "Docker 不可用，跳过容器冒烟");
        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            conn.createStatement().execute(
                    "CREATE TABLE smoke (id INT PRIMARY KEY, name VARCHAR(64))");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO smoke (id, name) VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "al-43");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM smoke WHERE id = ?")) {
                ps.setInt(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("al-43");
                }
            }
        }
    }

    @Test
    void redisRoundtrip() {
        assumeTrue(dockerAvailable(), "Docker 不可用，跳过容器冒烟");
        try (Jedis jedis = new Jedis(REDIS.getHost(), REDIS.getMappedPort(6379))) {
            jedis.set("smoke:al-43", "ok");
            assertThat(jedis.get("smoke:al-43")).isEqualTo("ok");
        }
    }
}
