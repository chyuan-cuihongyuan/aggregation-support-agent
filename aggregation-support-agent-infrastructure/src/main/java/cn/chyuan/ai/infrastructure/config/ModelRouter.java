package cn.chyuan.ai.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模型路由解析器（b-11 / 工单 1118）：按用例解析模型候选链。
 * 三级优先：路由表显式配置 &gt; 用例旧键 &gt; 硬编码默认——
 * 不配置路由表时行为与历史完全一致（零差异红线）。
 */
@Slf4j
@Component
public class ModelRouter {

    /** 历史单键回退：用例 → @Value 键（默认值随键内联） */
    private final Map<String, String> legacyKeys = Map.of(
            "query-optimization", "glm-4.5-flash");

    @Value("${rag.query.chat-model:glm-4.5-flash}")
    private String legacyQueryModel;

    private final ModelRoutingProperties properties;

    public ModelRouter(ModelRoutingProperties properties) {
        this.properties = properties;
    }

    /**
     * 候选链：路由表（model + fallbacks）优先；否则旧键；否则默认。
     */
    public List<String> candidates(String useCase) {
        List<String> chain = new ArrayList<>();
        ModelRoutingProperties.Route route = properties.getRouting().get(useCase);
        if (route != null && route.getModel() != null && !route.getModel().isBlank()) {
            chain.add(route.getModel());
            for (String fb : route.getFallbacks()) {
                if (fb != null && !fb.isBlank() && !chain.contains(fb)) {
                    chain.add(fb);
                }
            }
            return chain;
        }
        if ("query-optimization".equals(useCase) && legacyQueryModel != null && !legacyQueryModel.isBlank()) {
            chain.add(legacyQueryModel);
            return chain;
        }
        chain.add(legacyKeys.getOrDefault(useCase, "glm-4.5-flash"));
        return chain;
    }

    /**
     * 主选模型（候选链首位），并日志化完整候选链供排障。
     */
    public String resolveModel(String useCase) {
        List<String> chain = candidates(useCase);
        log.info("模型路由 [{}]: 主选={}, 候选链={}", useCase, chain.get(0), chain);
        return chain.get(0);
    }
}
