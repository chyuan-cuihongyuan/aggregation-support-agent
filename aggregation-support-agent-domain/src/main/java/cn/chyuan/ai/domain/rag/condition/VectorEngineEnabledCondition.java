package cn.chyuan.ai.domain.rag.condition;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 向量引擎装配条件（工单 0131，三期开关矩阵）：
 * pgvector.enabled（默认 true）或 milvus.enabled（过渡，默认 false）任一开启即装配。
 * 替代 RAG 服务原先仅挂 milvus.enabled 的单一条件——pgvector 默认启用后
 * RAG 链路必须随任一向量引擎激活。
 *
 * @author chyuan
 */
public class VectorEngineEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String pgvector = context.getEnvironment().getProperty("pgvector.enabled", "true");
        String milvus = context.getEnvironment().getProperty("milvus.enabled", "false");
        return !"false".equalsIgnoreCase(pgvector) || "true".equalsIgnoreCase(milvus);
    }
}
