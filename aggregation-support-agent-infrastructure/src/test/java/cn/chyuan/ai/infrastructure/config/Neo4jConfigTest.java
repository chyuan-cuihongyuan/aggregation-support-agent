package cn.chyuan.ai.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.neo4j.driver.Driver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Neo4j 配置外置 + 启动快速失败的行为测试（安全扫描整改 0016）。
 * 覆盖两条路径：启用但凭据缺失 → 启动失败并报出缺失配置项；
 * 凭据经属性注入 → 驱动 Bean 正常创建。
 */
class Neo4jConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Neo4jConfig.class);

    @Test
    void failsFastWhenEnabledButCredentialsMissing() {
        contextRunner
                .withPropertyValues("neo4j.enabled=true")
                .run(context -> {
                    context.assertThat().hasFailed();
                    String messages = collectFailureMessages(context);
                    assertThat(messages)
                            .contains("neo4j.uri")
                            .contains("neo4j.authentication.username")
                            .contains("neo4j.authentication.password");
                });
    }

    @Test
    void createsDriverWhenCredentialsInjected() {
        contextRunner
                .withPropertyValues(
                        "neo4j.enabled=true",
                        "neo4j.uri=bolt://127.0.0.1:7687",
                        "neo4j.authentication.username=neo4j",
                        "neo4j.authentication.password=injected-secret")
                .run(context -> {
                    context.assertThat().hasNotFailed();
                    assertThat(context.getBean(Driver.class)).isNotNull();
                });
    }

    private static String collectFailureMessages(AssertableApplicationContext context) {
        StringBuilder sb = new StringBuilder();
        Throwable current = context.getStartupFailure();
        for (int i = 0; current != null && i < 8; i++) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return sb.toString();
    }
}