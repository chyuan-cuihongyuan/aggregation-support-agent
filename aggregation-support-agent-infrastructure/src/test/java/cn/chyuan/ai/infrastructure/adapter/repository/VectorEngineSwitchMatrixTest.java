package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.infrastructure.adapter.repository.memory.AgentMemoryMilvusRepository;
import cn.chyuan.ai.infrastructure.adapter.repository.memory.AgentMemoryMySQLRepository;
import cn.chyuan.ai.infrastructure.adapter.repository.memory.AgentMemoryPgVectorRepository;
import cn.chyuan.ai.infrastructure.config.MilvusClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 向量引擎开关矩阵注解语义（工单 0131）：pgvector 默认启用、Milvus 过渡显式启用、
 * MySQL 降级链同时判定两个向量仓储的缺席。开关矩阵的真库行为由
 * {@code VectorEngineEnabledConditionTest}（domain）与健康检查端点共同覆盖。
 */
class VectorEngineSwitchMatrixTest {

    @Test
    void pgVectorRepositoriesDefaultEnabled() throws Exception {
        for (Class<?> clazz : new Class<?>[]{PgVectorVectorStoreRepository.class, AgentMemoryPgVectorRepository.class}) {
            ConditionalOnProperty conditional = clazz.getAnnotation(ConditionalOnProperty.class);
            assertTrue(conditional != null && "pgvector.enabled".equals(conditional.name()[0]),
                    clazz.getSimpleName() + " 应挂 pgvector.enabled");
            assertTrue(conditional.matchIfMissing(), clazz.getSimpleName() + " 应默认启用（matchIfMissing=true）");
            // 双引擎同开共存（用户口径：Milvus/pgvector 均长期支持）：
            // pgvector 为 @Primary 消除注入歧义，单开 milvus 则完整回退 Milvus 路径
            assertTrue(clazz.isAnnotationPresent(org.springframework.context.annotation.Primary.class),
                    clazz.getSimpleName() + " 应为 @Primary（两引擎同开时消除注入歧义）");
        }
    }

    @Test
    void milvusBeansAreOptIn() throws Exception {
        for (Class<?> clazz : new Class<?>[]{MilvusVectorStoreRepository.class, AgentMemoryMilvusRepository.class,
                MilvusClientConfig.class}) {
            ConditionalOnProperty conditional = clazz.getAnnotation(ConditionalOnProperty.class);
            assertTrue(conditional != null && "milvus.enabled".equals(conditional.name()[0]),
                    clazz.getSimpleName() + " 应挂 milvus.enabled");
            assertFalse(conditional.matchIfMissing(), clazz.getSimpleName() + " 应显式启用（过渡开关）");
        }
    }

    @Test
    void mySqlFallbackWaitsForBothVectorRepos() {
        // 经 toString 断言（对 Boot 版本的注解属性类型变化免疫）
        String declaration = java.util.Arrays.stream(AgentMemoryMySQLRepository.class.getAnnotations())
                .filter(a -> a.annotationType().getSimpleName().equals("ConditionalOnMissingBean"))
                .map(Object::toString)
                .findFirst().orElse("");
        assertTrue(declaration.contains("AgentMemoryMilvusRepository"), "降级链应判定 Milvus 缺席: " + declaration);
        assertTrue(declaration.contains("AgentMemoryPgVectorRepository"), "降级链应判定 pgvector 缺席: " + declaration);
    }
}
