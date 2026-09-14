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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Prometheus 指标与告警查询工具 — 接入真实 Prometheus，支持 Mock 模式回退
 */
@Slf4j
@Service
public class QueryMetricsTools {

    @Value("${aiops.prometheus.base-url}")
    private String prometheusBaseUrl;

    @Value("${aiops.prometheus.timeout}")
    private int prometheusTimeout;

    @Value("${aiops.mock.enabled}")
    private boolean mockEnabled;

    private final ToolResultSpillGuard spillGuard;

    private OkHttpClient httpClient;

    public QueryMetricsTools(ToolResultSpillGuard spillGuard) {
        this.spillGuard = spillGuard;
    }

    private final ObjectMapper objectMapper = ToolObjectMappers.create(); // b-52：共享 mapper（java.time/宽容未知字段）

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
     */
    @Tool(description = "查询 Prometheus 中当前所有活动告警，返回告警名称、严重程度、关联服务、当前指标值等详细信息")
    public String queryPrometheusAlerts() {
        log.info("工具调用: 查询 Prometheus 活动告警, mockEnabled={}", mockEnabled);

        try {
            if (mockEnabled) {
                PrometheusAlertsOutput mockOutput = buildMockAlerts();
                String result = objectMapper.writeValueAsString(spillGuard.capAlerts(mockOutput));
                log.info("Mock 模式返回模拟告警数据, 共 {} 条告警", mockOutput.getAlerts().size());
                return spillGuard.bound(result);
            }

            String url = prometheusBaseUrl + "/api/v1/alerts";
            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Prometheus API 调用失败: statusCode={}", response.code());
                    return objectMapper.writeValueAsString(buildErrorResponse("Prometheus API 调用失败: HTTP " + response.code()));
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                PrometheusAlertsOutput output = spillGuard.capAlerts(parsePrometheusAlertsResponse(responseBody));
                String result = objectMapper.writeValueAsString(output);
                log.info("Prometheus 告警查询完成, 共 {} 条活动告警", output.getAlerts().size());
                return spillGuard.bound(result);
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

    /**
     * 执行 PromQL 查询 — 查询即时向量数据
     */
    @Tool(description = "执行 PromQL 查询语句，获取 Prometheus 中的即时指标数据。例如查询 CPU 使用率、内存使用率、QPS 等指标")
    public String queryPromQL(
            @JsonProperty("query") @JsonPropertyDescription("PromQL 查询表达式，例如: 100 - (avg by(instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)") String query) {
        log.info("工具调用: PromQL 查询, query={}, mockEnabled={}", query, mockEnabled);

        try {
            if (mockEnabled) {
                return buildMockPromQLResult(query);
            }

            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = prometheusBaseUrl + "/api/v1/query?query=" + encodedQuery;
            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("PromQL 查询失败: statusCode={}", response.code());
                    return objectMapper.writeValueAsString(PromQLQueryOutput.builder()
                            .status("error")
                            .errorMessage("PromQL 查询失败: HTTP " + response.code())
                            .build());
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                PromQLQueryOutput output = parsePromQLResponse(responseBody);
                spillGuard.capTimeSeries(output.getResults());
                return spillGuard.bound(objectMapper.writeValueAsString(output));
            }

        } catch (Exception e) {
            log.error("PromQL 查询异常: query={}, error={}", query, e.getMessage(), e);
            try {
                return objectMapper.writeValueAsString(PromQLQueryOutput.builder()
                        .status("error")
                        .errorMessage("查询异常: " + e.getMessage())
                        .build());
            } catch (Exception ex) {
                return "{\"status\":\"error\",\"errorMessage\":\"查询异常\"}";
            }
        }
    }

    /**
     * 执行 PromQL 范围查询 — 获取时间范围内的指标趋势
     */
    @Tool(description = "执行 PromQL 范围查询，获取指定时间范围内的指标趋势数据，适用于绘制图表和分析趋势")
    public String queryPromQLRange(
            @JsonProperty("query") @JsonPropertyDescription("PromQL 查询表达式") String query,
            @JsonProperty("start") @JsonPropertyDescription("开始时间，RFC3339 格式或 Unix 时间戳，例如: 2025-01-01T00:00:00Z") String start,
            @JsonProperty("end") @JsonPropertyDescription("结束时间，RFC3339 格式或 Unix 时间戳") String end,
            @JsonProperty("step") @JsonPropertyDescription("查询步长，例如: 15s, 1m, 5m") String step) {
        log.info("工具调用: PromQL 范围查询, query={}, start={}, end={}, step={}", query, start, end, step);

        try {
            if (mockEnabled) {
                return buildMockPromQLResult(query);
            }

            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = prometheusBaseUrl + "/api/v1/query_range?query=" + encodedQuery
                    + "&start=" + URLEncoder.encode(start, StandardCharsets.UTF_8)
                    + "&end=" + URLEncoder.encode(end, StandardCharsets.UTF_8)
                    + "&step=" + URLEncoder.encode(step, StandardCharsets.UTF_8);
            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return objectMapper.writeValueAsString(PromQLQueryOutput.builder()
                            .status("error")
                            .errorMessage("范围查询失败: HTTP " + response.code())
                            .build());
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                PromQLQueryOutput output = parsePromQLResponse(responseBody);
                spillGuard.capTimeSeries(output.getResults());
                return spillGuard.bound(objectMapper.writeValueAsString(output));
            }

        } catch (Exception e) {
            log.error("PromQL 范围查询异常: {}", e.getMessage(), e);
            try {
                return objectMapper.writeValueAsString(PromQLQueryOutput.builder()
                        .status("error")
                        .errorMessage("查询异常: " + e.getMessage())
                        .build());
            } catch (Exception ex) {
                return "{\"status\":\"error\",\"errorMessage\":\"查询异常\"}";
            }
        }
    }

    // ========== 解析方法 ==========

    @SuppressWarnings("unchecked")
    private PrometheusAlertsOutput parsePrometheusAlertsResponse(String responseBody) {
        try {
            Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);
            List<SimplifiedAlert> alerts = new ArrayList<>();

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
            log.error("解析 Prometheus 告警响应失败: {}", e.getMessage(), e);
            return PrometheusAlertsOutput.builder()
                    .status("error")
                    .totalAlerts(0)
                    .alerts(new ArrayList<>())
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private PromQLQueryOutput parsePromQLResponse(String responseBody) {
        try {
            Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);
            String status = (String) response.getOrDefault("status", "unknown");

            PromQLQueryOutput.PromQLQueryOutputBuilder outputBuilder = PromQLQueryOutput.builder().status(status);

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data != null) {
                String resultType = (String) data.getOrDefault("resultType", "");
                outputBuilder.resultType(resultType);

                List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
                if (results != null) {
                    List<MetricResult> metricResults = new ArrayList<>();
                    for (Map<String, Object> result : results) {
                        Map<String, String> metric = (Map<String, String>) result.get("metric");
                        Object value = result.get("value");
                        List<List<Number>> values = (List<List<Number>>) result.get("values");

                        MetricResult mr = MetricResult.builder()
                                .metric(metric != null ? new HashMap<>(metric) : new HashMap<>())
                                .build();

                        if (value instanceof List) {
                            List<?> valList = (List<?>) value;
                            if (valList.size() == 2) {
                                mr.setTimestamp(String.valueOf(valList.get(0)));
                                mr.setValue(String.valueOf(valList.get(1)));
                            }
                        }

                        if (values != null) {
                            List<TimeSeriesPoint> points = new ArrayList<>();
                            for (List<Number> point : values) {
                                points.add(TimeSeriesPoint.builder()
                                        .timestamp(String.valueOf(point.get(0)))
                                        .value(String.valueOf(point.get(1)))
                                        .build());
                            }
                            mr.setTimeSeries(points);
                        }

                        metricResults.add(mr);
                    }
                    outputBuilder.results(metricResults);
                }
            }

            return outputBuilder.build();

        } catch (Exception e) {
            log.error("解析 PromQL 响应失败: {}", e.getMessage(), e);
            return PromQLQueryOutput.builder()
                    .status("error")
                    .errorMessage("解析响应失败: " + e.getMessage())
                    .build();
        }
    }

    private PrometheusAlertsOutput buildErrorResponse(String message) {
        return PrometheusAlertsOutput.builder()
                .status("error")
                .errorMessage(message)
                .totalAlerts(0)
                .alerts(new ArrayList<>())
                .build();
    }

    // ========== Mock 数据 ==========

    private PrometheusAlertsOutput buildMockAlerts() {
        List<SimplifiedAlert> alerts = new ArrayList<>();

        alerts.add(SimplifiedAlert.builder()
                .alertName("HighCPUUsage")
                .state("firing")
                .severity("critical")
                .serviceName("payment-service")
                .instance("127.0.0.1:8080")
                .currentValue("92%")
                .threshold("80%")
                .description("支付服务 CPU 使用率持续超过 80% 阈值已达 15 分钟，当前值 92%")
                .firedAt("2025-06-15T10:23:00Z")
                .labels(Map.of("team", "payment", "env", "production", "cluster", "prod-east-1"))
                .build());

        alerts.add(SimplifiedAlert.builder()
                .alertName("HighMemoryUsage")
                .state("firing")
                .severity("warning")
                .serviceName("order-service")
                .instance("127.0.0.1:8080")
                .currentValue("91%")
                .threshold("85%")
                .description("订单服务内存使用率持续超过 85% 阈值已达 10 分钟，当前值 91%")
                .firedAt("2025-06-15T10:25:00Z")
                .labels(Map.of("team", "order", "env", "production", "cluster", "prod-east-1"))
                .build());

        alerts.add(SimplifiedAlert.builder()
                .alertName("SlowResponse")
                .state("firing")
                .severity("warning")
                .serviceName("user-service")
                .instance("127.0.0.1:8080")
                .currentValue("4.2s")
                .threshold("2s")
                .description("用户服务 P99 响应时间持续超过 2s 阈值已达 5 分钟，当前值 4.2s")
                .firedAt("2025-06-15T10:28:00Z")
                .labels(Map.of("team", "user", "env", "production", "metric", "p99_latency"))
                .build());

        return PrometheusAlertsOutput.builder()
                .status("success")
                .totalAlerts(alerts.size())
                .alerts(alerts)
                .build();
    }

    private String buildMockPromQLResult(String query) throws Exception {
        List<MetricResult> results = new ArrayList<>();
        results.add(MetricResult.builder()
                .metric(Map.of("__name__", query.split("\\{")[0], "instance", "127.0.0.1:9090"))
                .value("78.5")
                .build());
        PromQLQueryOutput output = PromQLQueryOutput.builder()
                .status("success")
                .resultType("vector")
                .results(results)
                .build();
        return objectMapper.writeValueAsString(output);
    }

    // ========== 数据模型 ==========

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PrometheusAlertsOutput {
        @JsonProperty("status")
        private String status;
        @JsonProperty("totalAlerts")
        private int totalAlerts;
        @JsonProperty("alerts")
        private List<SimplifiedAlert> alerts;
        @JsonProperty("errorMessage")
        private String errorMessage;
        /** spill 围栏触发时的模型可见标记（未触发时省略） */
        @JsonProperty("spillNote")
        private String spillNote;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SimplifiedAlert {
        @JsonProperty("alertName")
        private String alertName;
        @JsonProperty("state")
        private String state;
        @JsonProperty("severity")
        private String severity;
        @JsonProperty("serviceName")
        private String serviceName;
        @JsonProperty("instance")
        private String instance;
        @JsonProperty("currentValue")
        private String currentValue;
        @JsonProperty("threshold")
        private String threshold;
        @JsonProperty("description")
        private String description;
        @JsonProperty("firedAt")
        private String firedAt;
        @JsonProperty("labels")
        private Map<String, String> labels;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PromQLQueryOutput {
        @JsonProperty("status")
        private String status;
        @JsonProperty("resultType")
        private String resultType;
        @JsonProperty("results")
        private List<MetricResult> results;
        @JsonProperty("errorMessage")
        private String errorMessage;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MetricResult {
        @JsonProperty("metric")
        private Map<String, String> metric;
        @JsonProperty("value")
        private String value;
        @JsonProperty("timestamp")
        private String timestamp;
        @JsonProperty("timeSeries")
        private List<TimeSeriesPoint> timeSeries;
        /** spill 围栏触发时的模型可见标记（未触发时省略） */
        @JsonProperty("spillNote")
        private String spillNote;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TimeSeriesPoint {
        @JsonProperty("timestamp")
        private String timestamp;
        @JsonProperty("value")
        private String value;
    }
}
