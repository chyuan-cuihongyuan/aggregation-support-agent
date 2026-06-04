package cn.chyuan.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 启动期关键配置校验。
 * <p>
 * 开发和测试环境只输出告警，生产类环境缺少关键配置时直接阻断启动，
 * 防止应用使用空密钥或示例值继续运行。
 */
@Slf4j
@Component
public class RequiredConfigurationValidator implements InitializingBean {

    private final Environment environment;

    public RequiredConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> missingKeys = collectMissingKeys();
        if (missingKeys.isEmpty()) {
            return;
        }

        if (isStrictProfile()) {
            throw new IllegalStateException("生产环境缺少关键配置: " + String.join(", ", missingKeys));
        }

        log.warn("当前环境缺少关键配置，相关能力在真实调用时可能不可用: {}", String.join(", ", missingKeys));
    }

    private List<String> collectMissingKeys() {
        List<String> missingKeys = new ArrayList<>();

        require(missingKeys, "auth.jwt.secret");

        String embeddingProvider = value("embedding.provider", "bigmodel").toLowerCase(Locale.ROOT);
        if ("bigmodel".equals(embeddingProvider)) {
            require(missingKeys, "bigmodel.api.key");
        } else if ("dashscope".equals(embeddingProvider)) {
            require(missingKeys, "dashscope.api.key");
        } else if ("deepseek".equals(embeddingProvider)) {
            require(missingKeys, "deepseek.api.key");
        }

        require(missingKeys, "ai-api.api-key");

        if (enabled("elasticsearch.enabled", false)) {
            require(missingKeys, "elasticsearch.password");
        }
        if (enabled("neo4j.enabled", false)) {
            require(missingKeys, "neo4j.authentication.password");
        }
        if (enabled("business.agent.enabled", false)) {
            require(missingKeys, "business.agent.auth-key");
        }
        if (enabled("observability.enabled", false) && enabled("observability.http.enabled", false)) {
            require(missingKeys, "observability.http.auth-key");
        }
        if ("cos".equalsIgnoreCase(value("storage.image.provider", ""))) {
            require(missingKeys, "storage.image.cos.secret-id");
            require(missingKeys, "storage.image.cos.secret-key");
        }

        return missingKeys;
    }

    private boolean isStrictProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod")
                        || profile.equals("release")
                        || profile.equals("pre")
                        || profile.equals("preprod")
                        || profile.equals("production"));
    }

    private boolean enabled(String key, boolean defaultValue) {
        return Boolean.parseBoolean(value(key, String.valueOf(defaultValue)));
    }

    private void require(List<String> missingKeys, String key) {
        String property = value(key, "");
        if (property.isBlank() || looksLikePlaceholder(property)) {
            missingKeys.add(key);
        }
    }

    private String value(String key, String defaultValue) {
        return environment.getProperty(key, defaultValue);
    }

    private boolean looksLikePlaceholder(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("replace")
                || normalized.contains("changeme")
                || normalized.contains("your-")
                || normalized.contains("sk-xxx")
                || normalized.equals("secret")
                || normalized.equals("password");
    }
}
