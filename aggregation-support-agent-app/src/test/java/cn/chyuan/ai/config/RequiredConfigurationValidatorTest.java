package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RequiredConfigurationValidator 的 Neo4j 快速失败行为测试（安全扫描整改 0016）。
 * 覆盖：neo4j.enabled=true 且缺少 uri/username/password 时，生产类环境启动失败并报出缺失项；
 * 注入完整凭据后不再失败；未启用 neo4j 时不要求凭据。
 * 注：项目 test starter 使用 JUnit Jupiter provider（无 vintage），故用 JUnit5 断言。
 */
class RequiredConfigurationValidatorTest {

    private MockEnvironment envWithBaseRequirements() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("auth.jwt.secret", "test-secret");
        env.setProperty("bigmodel.api.key", "test-key");
        env.setProperty("ai-api.api-key", "test-key");
        return env;
    }

    @Test
    void neo4jEnabledWithMissingCredentials_failsFastInProd() {
        MockEnvironment env = envWithBaseRequirements();
        env.setProperty("neo4j.enabled", "true");
        env.setActiveProfiles("prod");

        RequiredConfigurationValidator validator = new RequiredConfigurationValidator(env);

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("neo4j.uri")
                .hasMessageContaining("neo4j.authentication.username")
                .hasMessageContaining("neo4j.authentication.password");
    }

    @Test
    void neo4jEnabledWithFullCredentials_noFailure() {
        MockEnvironment env = envWithBaseRequirements();
        env.setProperty("neo4j.enabled", "true");
        env.setProperty("neo4j.uri", "bolt://127.0.0.1:7687");
        env.setProperty("neo4j.authentication.username", "neo4j");
        env.setProperty("neo4j.authentication.password", "injected-secret");
        env.setActiveProfiles("prod");

        // 不抛异常即通过
        new RequiredConfigurationValidator(env).afterPropertiesSet();
    }

    @Test
    void neo4jDisabled_doesNotRequireCredentials() {
        MockEnvironment env = envWithBaseRequirements();
        env.setActiveProfiles("prod");

        // neo4j.enabled 缺省 false，不要求 Neo4j 配置
        new RequiredConfigurationValidator(env).afterPropertiesSet();
    }
}