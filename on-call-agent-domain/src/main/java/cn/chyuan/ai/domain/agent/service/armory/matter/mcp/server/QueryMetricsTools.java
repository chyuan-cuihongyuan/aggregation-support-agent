package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Prometheus 告警查询工具 — 查询 Prometheus 的活动告警信息，支持 Mock 模式
 * <p>
 * 在 AIOps 场景中，智能体需要获取当前系统的活动告警来辅助故障诊断：
 * <ul>
 *   <li>查询 Prometheus 中所有正在触发的告警规则</li>
 *   <li>获取告警的严重程度、服务名称、当前值等详细信息</li>
 *   <li>辅助根因分析和故障影响范围评估</li>
 * </ul>
 * <p>
 * 支持两种运行模式：
 * <ul>
 *   <li>Mock 模式：返回预定义的模拟告警数据，用于开发和测试</li>
 *   <li>真实模式：通过 OkHttp 调用 Prometheus HTTP API 获取实时告警数据</li>
 * </ul>
 * <p>
 * 迁移自 OnCall-Agent-java 项目，作为本地 MCP 工具注册为 Spring Bean。
 */
@Slf4j
@Service
public class QueryMetricsTools {

    /** Prometheus 服务器基础地址，例如 http://prometheus:9090 */
    @Value("${prometheus.base-url:http://localhost:9090}")
    private String prometheusBaseUrl;

    /** Prometheus API 请求超时时间（秒） */
    @Value("${prometheus.timeout:10}")
    private int prometheusTimeout;

    /** 是否启用 Mock 模式，为 true 时返回模拟数据而不调用真实 Prometheus */
    @Value("${aiops.mock-enabled:true}")
    private boolean mockEnabled;

    /** OkHttp 客户端，用于调用 Prometheus HTTP API */
    private OkHttpClient httpClient;

    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 初始化 OkHttp 客户端
     * <p>
     * 在 Bean 构造完成后初始化，配置连接超时和读取超时时间
     */
    @PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(prometheusTimeout, TimeUnit.SECONDS)
                .readTimeout(prometheusTimeout, TimeUnit.SECONDS)
                .writeTimeout(prometheusTimeout, TimeUnit.SECONDS)
                .build();
        log.info("QueryMetricsTools 初始化完成, mockEnabled={}, prometheusBaseUrl={}", mockEnabled, prometheusBaseUrl);
    }

    /**
     * 查询 Prometheus 活动告警
     * <p>
     * 调用 Prometheus /api/v1/alerts 接口获取当前所有活动告警，
     * 或在 Mock 模式下返回预定义的模拟告警数据。
     *
     * @return JSON 格式的告警列表，包含告警名称、状态、严重程度、服务信息等
     */
    @Tool(description = "查询 Prometheus 中当前所有活动告警，返回告警名称、严重程度、关联服务、当前指标值等详细信息")
    public String queryPrometheusAlerts() {
        log.info("工具调用: 查询 Prometheus 活动告警, mockEnabled={}", mockEnabled);

        try {
            // Mock 模式直接返回模拟告警数据
            if (mockEnabled) {
                PrometheusAlertsOutput mockOutput = buildMockAlerts();
                String result = objectMapper.writeValueAsString(mockOutput);
                log.info("Mock 模式返回模拟告警数据, 共 {} 条告警", mockOutput.getAlerts().size());
                return result;
            }

            // 真实模式：调用 Prometheus API 查询活动告警
            String url = prometheusBaseUrl + "/api/v1/alerts";
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Prometheus API 调用失败: statusCode={}", response.code());
                    return objectMapper.writeValueAsString(buildErrorResponse("Prometheus API 调用失败: HTTP " + response.code()));
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                // 将 Prometheus 原始响应转换为简化的告警输出
                PrometheusAlertsOutput output = parsePrometheusResponse(responseBody);
                String result = objectMapper.writeValueAsString(output);
                log.info("Prometheus 告警查询完成, 共 {} 条活动告警", output.getAlerts().size());
                return result;
            }

        } catch (Exception e) {
            log.error("查询 Prometheus 告警失败: {}", e.getMessage(), e);
            try {
                return objectMapper.writeValueAsString(buildErrorResponse("查询失败: " + e.getMessage()));
            } catch (Exception jsonException) {
                return "{\"error\":true,\"message\":\"查询失败且结果序列化异常\"}";
            }
        }
    }

    // ========== 模拟数据构建方法 ==========

    /**
     * 构建模拟告警数据
     * <p>
     * 提供三条典型的 AIOps 场景模拟告警，覆盖 CPU、内存和响应时间三个维度
     *
     * @return 包含模拟告警的输出对象
     */
    private PrometheusAlertsOutput buildMockAlerts() {
        List<SimplifiedAlert> alerts = new ArrayList<>();

        // 告警 1：支付服务 CPU 使用率过高
        alerts.add(SimplifiedAlert.builder()
                .alertName("HighCPUUsage")
                .state("firing")
                .severity("critical")
                .serviceName("payment-service")
                .instance("10.0.1.15:8080")
                .currentValue("92%")
                .threshold("80%")
                .description("支付服务 CPU 使用率持续超过 80% 阈值已达 15 分钟，当前值 92%")
                .firedAt("2025-06-15T10:23:00Z")
                .labels(Map.of(
                        "team", "payment",
                        "env", "production",
                        "cluster", "prod-east-1",
                        "namespace", "payment-system"
                ))
                .build());

        // 告警 2：订单服务内存使用率过高
        alerts.add(SimplifiedAlert.builder()
                .alertName("HighMemoryUsage")
                .state("firing")
                .severity("warning")
                .serviceName("order-service")
                .instance("10.0.2.22:8080")
                .currentValue("91%")
                .threshold("85%")
                .description("订单服务内存使用率持续超过 85% 阈值已达 10 分钟，当前值 91%")
                .firedAt("2025-06-15T10:25:00Z")
                .labels(Map.of(
                        "team", "order",
                        "env", "production",
                        "cluster", "prod-east-1",
                        "namespace", "order-system"
                ))
                .build());

        // 告警 3：用户服务响应时间过慢
        alerts.add(SimplifiedAlert.builder()
                .alertName("SlowResponse")
                .state("firing")
                .severity("warning")
                .serviceName("user-service")
                .instance("10.0.3.18:8080")
                .currentValue("4.2s")
                .threshold("2s")
                .description("用户服务 P99 响应时间持续超过 2s 阈值已达 5 分钟，当前值 4.2s")
                .firedAt("2025-06-15T10:28:00Z")
                .labels(Map.of(
                        "team", "user",
                        "env", "production",
                        "cluster", "prod-east-1",
                        "namespace", "user-system",
                        "metric", "p99_latency"
                ))
                .build());

        return PrometheusAlertsOutput.builder()
                .status("success")
                .totalAlerts(alerts.size())
                .alerts(alerts)
                .build();
    }

    /**
     * 解析 Prometheus API 原始响应
     * <p>
     * 将 Prometheus /api/v1/alerts 返回的 JSON 响应转换为简化的告警输出对象
     *
     * @param responseBody Prometheus API 的原始 JSON 响应
     * @return 简化后的告警输出对象
     */
    @SuppressWarnings("unchecked")
    private PrometheusAlertsOutput parsePrometheusResponse(String responseBody) {
        try {
            Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);
            List<SimplifiedAlert> alerts = new ArrayList<>();

            // 提取 alerts 数组
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data != null) {
                List<Map<String, Object>> alertList = (List<Map<String, Object>>) data.get("alerts");
                if (alertList != null) {
                    for (Map<String, Object> alertRaw : alertList) {
                        Map<String, String> labels = (Map<String, String>) alertRaw.get("labels");
                        Map<String, String> annotations = (Map<String, String>) alertRaw.get("annotations");

                        SimplifiedAlert alert = SimplifiedAlert.builder()
                                .alertName(labels != null ? labels.getOrDefault("alertname", "unknown") : "unknown")
                                .state((String) alertRaw.getOrDefault("state", "unknown"))
                                .severity(labels != null ? labels.getOrDefault("severity", "unknown") : "unknown")
                                .serviceName(labels != null ? labels.getOrDefault("service", "unknown") : "unknown")
                                .instance(labels != null ? labels.getOrDefault("instance", "unknown") : "unknown")
                                .description(annotations != null ? annotations.getOrDefault("description", "") : "")
                                .firedAt((String) alertRaw.getOrDefault("activeAt", ""))
                                .labels(labels != null ? new HashMap<>(labels) : new HashMap<>())
                                .build();

                        alerts.add(alert);
                    }
                }
            }

            return PrometheusAlertsOutput.builder()
                    .status("success")
                    .totalAlerts(alerts.size())
                    .alerts(alerts)
                    .build();

        } catch (Exception e) {
            log.error("解析 Prometheus 响应失败: {}", e.getMessage(), e);
            return PrometheusAlertsOutput.builder()
                    .status("error")
                    .totalAlerts(0)
                    .alerts(new ArrayList<>())
                    .build();
        }
    }

    /**
     * 构建错误响应
     *
     * @param message 错误信息
     * @return 错误响应对象
     */
    private PrometheusAlertsOutput buildErrorResponse(String message) {
        return PrometheusAlertsOutput.builder()
                .status("error")
                .errorMessage(message)
                .totalAlerts(0)
                .alerts(new ArrayList<>())
                .build();
    }

    // ========== 内部数据模型类 ==========

    /**
     * Prometheus 告警查询输出 — 封装告警查询的整体结果
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PrometheusAlertsOutput {

        /** 查询状态：success 或 error */
        @JsonProperty("status")
        @JsonPropertyDescription("查询状态")
        private String status;

        /** 活动告警总数 */
        @JsonProperty("totalAlerts")
        @JsonPropertyDescription("活动告警总数")
        private int totalAlerts;

        /** 告警列表 */
        @JsonProperty("alerts")
        @JsonPropertyDescription("活动告警详情列表")
        private List<SimplifiedAlert> alerts;

        /** 错误信息（仅在查询失败时返回） */
        @JsonProperty("errorMessage")
        @JsonPropertyDescription("错误信息")
        private String errorMessage;
    }

    /**
     * 简化的告警信息 — 从 Prometheus 告警中提取关键信息
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SimplifiedAlert {

        /** 告警规则名称，例如 HighCPUUsage */
        @JsonProperty("alertName")
        @JsonPropertyDescription("告警名称")
        private String alertName;

        /** 告警状态：firing（触发中）、pending（等待中）、resolved（已恢复） */
        @JsonProperty("state")
        @JsonPropertyDescription("告警状态")
        private String state;

        /** 严重程度：critical（严重）、warning（警告）、info（信息） */
        @JsonProperty("severity")
        @JsonPropertyDescription("严重程度")
        private String severity;

        /** 关联的服务名称 */
        @JsonProperty("serviceName")
        @JsonPropertyDescription("关联服务名称")
        private String serviceName;

        /** 告警实例地址 */
        @JsonProperty("instance")
        @JsonPropertyDescription("实例地址")
        private String instance;

        /** 当前指标值 */
        @JsonProperty("currentValue")
        @JsonPropertyDescription("当前指标值")
        private String currentValue;

        /** 告警阈值 */
        @JsonProperty("threshold")
        @JsonPropertyDescription("告警阈值")
        private String threshold;

        /** 告警描述信息 */
        @JsonProperty("description")
        @JsonPropertyDescription("告警描述")
        private String description;

        /** 告警触发时间（ISO 8601 格式） */
        @JsonProperty("firedAt")
        @JsonPropertyDescription("告警触发时间")
        private String firedAt;

        /** 告警标签（包含团队、环境、集群等额外信息） */
        @JsonProperty("labels")
        @JsonPropertyDescription("告警标签")
        private Map<String, String> labels;
    }
}
