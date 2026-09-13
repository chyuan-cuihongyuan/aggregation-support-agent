package cn.chyuan.ai.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型路由表（b-11 / 工单 1118，借鉴 LiteLLM router 配置形态）：
 * <pre>
 * ai-api:
 *   routing:
 *     query-optimization:
 *       model: glm-4.5-flash
 *       fallbacks: [glm-4-flash]
 * </pre>
 * 只承载「静态构建点」的模型路由；armory 装配面由 DB 配置表驱动，不经此表。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-api")
public class ModelRoutingProperties {

    /** 用例键 → 路由（model 为主选，fallbacks 为候选顺序） */
    private Map<String, Route> routing = new LinkedHashMap<>();

    @Data
    public static class Route {
        /** 主选模型 */
        private String model;
        /** 候选顺序（v1 仅日志化输出，执行语义见工单雾区） */
        private java.util.List<String> fallbacks = new java.util.ArrayList<>();
    }
}
