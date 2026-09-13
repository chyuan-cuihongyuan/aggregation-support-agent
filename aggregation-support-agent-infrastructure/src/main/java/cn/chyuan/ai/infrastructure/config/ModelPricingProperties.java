package cn.chyuan.ai.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型单价表（b-13 / 工单 1122，借鉴 LiteLLM cost tracking 的价格表形态）：
 * <pre>
 * ai-api:
 *   pricing:
 *     glm-4.5-flash:
 *       input-per-1k-usd: 0.0
 *       output-per-1k-usd: 0.0
 * </pre>
 * 单位：USD / 1k tokens；未配置的模型只记 token 数，成本记 0。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-api")
public class ModelPricingProperties {

    /** 模型名 → 单价 */
    private Map<String, Pricing> pricing = new LinkedHashMap<>();

    @Data
    public static class Pricing {
        /** 输入单价（USD / 1k tokens） */
        private double inputPer1kUsd = 0.0;
        /** 输出单价（USD / 1k tokens） */
        private double outputPer1kUsd = 0.0;
    }
}
