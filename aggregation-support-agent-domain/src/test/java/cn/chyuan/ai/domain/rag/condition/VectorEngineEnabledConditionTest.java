package cn.chyuan.ai.domain.rag.condition;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 向量引擎开关矩阵（工单 0131）：任一引擎开启即装配 RAG 链路。 */
class VectorEngineEnabledConditionTest {

    private final VectorEngineEnabledCondition condition = new VectorEngineEnabledCondition();

    private boolean matches(Map<String, String> props) {
        Environment environment = mock(Environment.class);
        when(environment.getProperty(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    String defaultValue = inv.getArgument(1);
                    return props.getOrDefault(key, defaultValue);
                });
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return condition.matches(context, null);
    }

    @Test
    void defaultConfigEnablesEngine() {
        // 全默认：pgvector.enabled=true → 装配
        assertTrue(matches(Map.of()), "默认应装配（pgvector 默认开）");
    }

    @Test
    void pgvectorExplicitOnEnablesEngine() {
        assertTrue(matches(Map.of("pgvector.enabled", "true")));
    }

    @Test
    void milvusTransitionStillEnablesEngine() {
        // 过渡矩阵：pgvector 关 + milvus 开 → 仍装配（Milvus 路径可用）
        assertTrue(matches(Map.of("pgvector.enabled", "false", "milvus.enabled", "true")));
    }

    @Test
    void bothOffDisablesEngine() {
        assertFalse(matches(Map.of("pgvector.enabled", "false", "milvus.enabled", "false")));
    }
}
