package cn.chyuan.ai.domain.agent.support.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PromptRegistry：Langfuse 式 name+version 注册制")
class PromptRegistryTest {

    @Test
    @DisplayName("注册后可取当前版本；重复注册同内容幂等")
    void registerAndGet() {
        PromptRegistry.register("test.hello", "v1", "hello world");
        assertThat(PromptRegistry.current("test.hello")).contains("hello world");
        assertThat(PromptRegistry.currentVersion("test.hello")).isEqualTo("v1");
        assertThatCode(() -> PromptRegistry.register("test.hello", "v1", "hello world"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("同名同版本不同内容拒绝（暴露漂移）")
    void conflictRejected() {
        PromptRegistry.register("test.conflict", "v1", "a");
        assertThatThrownBy(() -> PromptRegistry.register("test.conflict", "v1", "b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflict");
    }

    @Test
    @DisplayName("当前版本取数值语义最大者（v2 > v10 为假）")
    void versionCompare() {
        PromptRegistry.register("test.versions", "v2", "two");
        PromptRegistry.register("test.versions", "v10", "ten");
        assertThat(PromptRegistry.currentVersion("test.versions")).isEqualTo("v10");
        assertThat(PromptRegistry.get("test.versions", "v2")).contains("two");
    }

    @Test
    @DisplayName("未注册返回 empty；空白参数拒绝")
    void missingAndInvalid() {
        assertThat(PromptRegistry.current("test.missing")).isEmpty();
        assertThat(PromptRegistry.get("test.missing", "v1")).isEmpty();
        assertThatThrownBy(() -> PromptRegistry.register(" ", "v1", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("版本段数值补齐比较：v9 < v10")
    void paddedNumericSegments() {
        assertThat(PromptRegistry.compareVersions("v10", "v9")).isPositive();
        assertThat(PromptRegistry.compareVersions("v2.1", "v2")).isPositive();
    }
}
