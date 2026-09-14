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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 日志查询工具 — 接入 Loki 日志聚合系统，支持 Mock 模式回退
 */
@Slf4j
@Service
public class QueryLogsTools {

    @Value("${aiops.mock.enabled}")
    private boolean mockEnabled;

    @Value("${aiops.loki.base-url}")
    private String lokiBaseUrl;

    @Value("${aiops.loki.timeout}")
    private int lokiTimeout;

    private final ToolResultSpillGuard spillGuard;

    private OkHttpClient httpClient;

    public QueryLogsTools(ToolResultSpillGuard spillGuard) {
        this.spillGuard = spillGuard;
    }

    private final ObjectMapper objectMapper = ToolObjectMappers.create(); // b-52：共享 mapper（java.time/宽容未知字段）

    @PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(lokiTimeout, TimeUnit.SECONDS)
                .readTimeout(lokiTimeout, TimeUnit.SECONDS)
                .writeTimeout(lokiTimeout, TimeUnit.SECONDS)
                .build();
        log.info("QueryLogsTools 初始化完成, mockEnabled={}, lokiBaseUrl={}", mockEnabled, lokiBaseUrl);
    }

    /**
     * 获取可用的日志主题列表
     */
    @Tool(description = "获取所有可用的日志主题列表，包含主题名称和描述，用于后续日志查询时指定目标主题")
    public String getAvailableLogTopics() {
        log.info("工具调用: 获取可用日志主题列表");

        try {
            List<LogTopic> topics = buildLogTopics();
            Map<String, Object> response = new HashMap<>();
            response.put("totalTopics", topics.size());
            response.put("topics", topics);

            String result = objectMapper.writeValueAsString(response);
            log.info("返回 {} 个日志主题", topics.size());
            return spillGuard.bound(result);

        } catch (Exception e) {
            log.error("获取日志主题列表失败: {}", e.getMessage(), e);
            return "{\"error\":true,\"message\":\"获取日志主题列表失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 查询日志 — 从 Loki 检索或 Mock 模式
     */
    @Tool(description = "从指定日志主题中查询日志，支持按地域、主题、关键词过滤，返回匹配的日志条目。真实模式从 Loki 聚合系统查询")
    public String queryLogs(String region, String logTopic, String query, int limit) {
        log.info("工具调用: 查询日志, region={}, logTopic={}, query={}, limit={}, mockEnabled={}",
                region, logTopic, query, limit, mockEnabled);

        try {
            if (mockEnabled) {
                LogQueryOutput mockOutput = buildMockLogs(logTopic, query, limit);
                String result = objectMapper.writeValueAsString(spillGuard.capLogs(mockOutput));
                log.info("Mock 模式返回模拟日志数据, 共 {} 条日志", mockOutput.getLogs().size());
                return spillGuard.bound(result);
            }

            return queryLogsFromLoki(logTopic, query, limit);

        } catch (Exception e) {
            log.error("查询日志失败: logTopic={}, query={}, 错误: {}", logTopic, query, e.getMessage(), e);
            try {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", true);
                errorResponse.put("message", "日志查询失败: " + e.getMessage());
                return objectMapper.writeValueAsString(errorResponse);
            } catch (Exception jsonException) {
                return "{\"error\":true,\"message\":\"日志查询失败且结果序列化异常\"}";
            }
        }
    }

    /**
     * 使用 LogQL 查询 Loki 日志
     */
    @Tool(description = "使用 LogQL 查询表达式从 Loki 中检索日志，支持高级过滤和标签匹配。例如: {service=\"payment-service\"} |= \"ERROR\"")
    public String queryLogsByLogQL(
            @JsonProperty("logql") @JsonPropertyDescription("LogQL 查询表达式，例如: {service=\"payment-service\"} |= \"ERROR\" | json | line_format \"{{.message}}\"") String logql,
            @JsonProperty("limit") @JsonPropertyDescription("返回的最大日志条数，默认 100") int limit,
            @JsonProperty("timeRange") @JsonPropertyDescription("时间范围，例如: 1h, 6h, 24h, 7d") String timeRange) {
        log.info("工具调用: LogQL 查询, logql={}, limit={}, timeRange={}", logql, limit, timeRange);

        try {
            if (mockEnabled) {
                LogQueryOutput mockOutput = buildMockLogs("application-logs", "ERROR", limit > 0 ? limit : 100);
                return spillGuard.bound(objectMapper.writeValueAsString(spillGuard.capLogs(mockOutput)));
            }

            int actualLimit = limit > 0 ? limit : 100;
            int rangeMinutes = parseTimeRange(timeRange);

            long endNs = Instant.now().toEpochMilli() * 1_000_000L;
            long startNs = Instant.now().minus(rangeMinutes, ChronoUnit.MINUTES).toEpochMilli() * 1_000_000L;

            String encodedQuery = URLEncoder.encode(logql, StandardCharsets.UTF_8);
            String url = lokiBaseUrl + "/loki/api/v1/query_range"
                    + "?query=" + encodedQuery
                    + "&limit=" + actualLimit
                    + "&start=" + startNs
                    + "&end=" + endNs
                    + "&direction=backward";

            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Loki API 调用失败: statusCode={}", response.code());
                    String errorBody = response.body() != null ? response.body().string() : "";
                    return objectMapper.writeValueAsString(LogQueryOutput.builder()
                            .status("error")
                            .errorMessage("Loki API 调用失败: HTTP " + response.code() + ", " + errorBody)
                            .totalLogs(0)
                            .logs(new ArrayList<>())
                            .build());
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                LogQueryOutput output = parseLokiResponse(responseBody);
                return spillGuard.bound(objectMapper.writeValueAsString(spillGuard.capLogs(output)));
            }

        } catch (Exception e) {
            log.error("LogQL 查询失败: logql={}, error={}", logql, e.getMessage(), e);
            try {
                return objectMapper.writeValueAsString(LogQueryOutput.builder()
                        .status("error")
                        .errorMessage("查询失败: " + e.getMessage())
                        .totalLogs(0)
                        .logs(new ArrayList<>())
                        .build());
            } catch (Exception ex) {
                return "{\"status\":\"error\",\"errorMessage\":\"查询异常\"}";
            }
        }
    }

    // ========== Loki 查询 ==========

    private String queryLogsFromLoki(String logTopic, String query, int limit) throws Exception {
        String logql = buildLogQL(logTopic, query);
        int actualLimit = limit > 0 ? limit : 100;

        long endNs = Instant.now().toEpochMilli() * 1_000_000L;
        long startNs = Instant.now().minus(1, ChronoUnit.HOURS).toEpochMilli() * 1_000_000L;

        String encodedQuery = URLEncoder.encode(logql, StandardCharsets.UTF_8);
        String url = lokiBaseUrl + "/loki/api/v1/query_range"
                + "?query=" + encodedQuery
                + "&limit=" + actualLimit
                + "&start=" + startNs
                + "&end=" + endNs
                + "&direction=backward";

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("Loki API 调用失败: statusCode={}", response.code());
                return objectMapper.writeValueAsString(LogQueryOutput.builder()
                        .status("error")
                        .errorMessage("Loki API 调用失败: HTTP " + response.code())
                        .totalLogs(0)
                        .logs(new ArrayList<>())
                        .build());
            }

            String responseBody = response.body() != null ? response.body().string() : "{}";
            LogQueryOutput output = parseLokiResponse(responseBody);
            output.setLogTopic(logTopic);
            output.setQuery(query);
            return spillGuard.bound(objectMapper.writeValueAsString(spillGuard.capLogs(output)));
        }
    }

    private String buildLogQL(String logTopic, String query) {
        StringBuilder logql = new StringBuilder("{");
        switch (logTopic) {
            case "system-metrics":
                logql.append("job=\"varlogs\"");
                break;
            case "application-logs":
                logql.append("level=~\"ERROR|WARN\"");
                break;
            case "database-slow-query":
                logql.append("level=\"WARN\"");
                break;
            case "system-events":
                logql.append("job=\"varlogs\"");
                break;
            default:
                logql.append("job=~\".*\"");
                break;
        }
        logql.append("}");

        if (query != null && !query.trim().isEmpty()) {
            logql.append(" |= \"").append(query.replace("\"", "\\\"")).append("\"");
        }

        return logql.toString();
    }

    @SuppressWarnings("unchecked")
    private LogQueryOutput parseLokiResponse(String responseBody) {
        try {
            Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);
            String status = (String) response.getOrDefault("status", "unknown");

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            List<LogEntry> logs = new ArrayList<>();

            if (data != null) {
                List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
                if (results != null) {
                    for (Map<String, Object> result : results) {
                        Map<String, String> stream = (Map<String, String>) result.get("stream");
                        List<List<Object>> values = (List<List<Object>>) result.get("values");

                        if (values != null) {
                            for (List<Object> valuePair : values) {
                                String timestamp = valuePair.size() > 0 ? String.valueOf(valuePair.get(0)) : "";
                                String line = valuePair.size() > 1 ? String.valueOf(valuePair.get(1)) : "";

                                Map<String, Object> metadata = new HashMap<>();
                                if (stream != null) {
                                    metadata.putAll(stream);
                                }

                                String level = stream != null ? stream.getOrDefault("level", "INFO") : "INFO";
                                String service = stream != null ? stream.getOrDefault("service", "unknown") : "unknown";
                                String source = stream != null ? stream.getOrDefault("container", "unknown") : "unknown";

                                logs.add(LogEntry.builder()
                                        .timestamp(formatLokiTimestamp(timestamp))
                                        .level(level)
                                        .source(source)
                                        .serviceName(service)
                                        .message(line)
                                        .metadata(metadata)
                                        .build());
                            }
                        }
                    }
                }
            }

            return LogQueryOutput.builder()
                    .status(status)
                    .totalLogs(logs.size())
                    .logs(logs)
                    .build();

        } catch (Exception e) {
            log.error("解析 Loki 响应失败: {}", e.getMessage(), e);
            return LogQueryOutput.builder()
                    .status("error")
                    .errorMessage("解析响应失败: " + e.getMessage())
                    .totalLogs(0)
                    .logs(new ArrayList<>())
                    .build();
        }
    }

    private String formatLokiTimestamp(String nsTimestamp) {
        try {
            long millis = Long.parseLong(nsTimestamp) / 1_000_000;
            return Instant.ofEpochMilli(millis).toString();
        } catch (Exception e) {
            return nsTimestamp;
        }
    }

    private int parseTimeRange(String timeRange) {
        if (timeRange == null || timeRange.isEmpty()) {
            return 60;
        }
        try {
            String lower = timeRange.toLowerCase();
            if (lower.endsWith("d")) {
                return Integer.parseInt(lower.replace("d", "")) * 24 * 60;
            } else if (lower.endsWith("h")) {
                return Integer.parseInt(lower.replace("h", "")) * 60;
            } else if (lower.endsWith("m")) {
                return Integer.parseInt(lower.replace("m", ""));
            }
            return Integer.parseInt(timeRange);
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    // ========== 日志主题 ==========

    private List<LogTopic> buildLogTopics() {
        List<LogTopic> topics = new ArrayList<>();

        topics.add(LogTopic.builder()
                .name("system-metrics")
                .displayName("系统指标日志")
                .description("包含 CPU 使用率、内存使用率、磁盘 I/O、网络流量等系统级指标日志")
                .retentionDays(30)
                .build());

        topics.add(LogTopic.builder()
                .name("application-logs")
                .displayName("应用运行日志")
                .description("包含应用的 INFO、WARN、ERROR 级别日志，异常堆栈信息等")
                .retentionDays(15)
                .build());

        topics.add(LogTopic.builder()
                .name("database-slow-query")
                .displayName("数据库慢查询日志")
                .description("包含执行时间超过阈值的 SQL 查询日志，含完整 SQL 语句和执行计划")
                .retentionDays(30)
                .build());

        topics.add(LogTopic.builder()
                .name("system-events")
                .displayName("系统事件日志")
                .description("包含服务部署、扩缩容、配置变更、重启等系统级事件日志")
                .retentionDays(90)
                .build());

        return topics;
    }

    // ========== Mock 数据 ==========

    private LogQueryOutput buildMockLogs(String logTopic, String query, int limit) {
        List<LogEntry> allLogs = new ArrayList<>();

        switch (logTopic) {
            case "system-metrics":
                allLogs.addAll(buildSystemMetricsLogs());
                break;
            case "application-logs":
                allLogs.addAll(buildApplicationLogs());
                break;
            case "database-slow-query":
                allLogs.addAll(buildDatabaseSlowQueryLogs());
                break;
            case "system-events":
                allLogs.addAll(buildSystemEventLogs());
                break;
            default:
                log.warn("未知的日志主题: {}", logTopic);
                break;
        }

        List<LogEntry> filteredLogs = filterLogs(allLogs, query);
        List<LogEntry> resultLogs = filteredLogs.stream()
                .limit(limit > 0 ? limit : 10)
                .collect(Collectors.toList());

        return LogQueryOutput.builder()
                .status("success")
                .logTopic(logTopic)
                .query(query)
                .totalLogs(resultLogs.size())
                .logs(resultLogs)
                .build();
    }

    private List<LogEntry> buildSystemMetricsLogs() {
        List<LogEntry> logs = new ArrayList<>();
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:22:15.000Z")
                .level("WARN")
                .source("127.0.0.1")
                .serviceName("payment-service")
                .message("CPU 使用率达到 92%，超过告警阈值 80%")
                .metadata(Map.of("metric", "cpu_usage_percent", "value", "92", "threshold", "80", "duration", "15m"))
                .build());
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:23:30.000Z")
                .level("WARN")
                .source("127.0.0.1")
                .serviceName("order-service")
                .message("内存使用率达到 91%，超过告警阈值 85%")
                .metadata(Map.of("metric", "memory_usage_percent", "value", "91", "threshold", "85", "duration", "10m"))
                .build());
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:24:00.000Z")
                .level("INFO")
                .source("127.0.0.1")
                .serviceName("payment-service")
                .message("磁盘 I/O 等待时间升高，iowait 达到 35%")
                .metadata(Map.of("metric", "disk_iowait_percent", "value", "35", "normal", "<10"))
                .build());
        return logs;
    }

    private List<LogEntry> buildApplicationLogs() {
        List<LogEntry> logs = new ArrayList<>();
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:22:45.123Z")
                .level("ERROR")
                .source("127.0.0.1")
                .serviceName("payment-service")
                .message("PaymentProcessingException: 支付回调处理失败，第三方网关超时")
                .metadata(Map.of("thread", "payment-callback-3", "traceId", "trace-abc123",
                        "exception", "PaymentProcessingException",
                        "stackTrace", "PaymentProcessingException: 第三方网关超时\n  at cn.chyuan.payment.callback.PaymentCallbackHandler.process(PaymentCallbackHandler.java:45)"))
                .build());
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:23:15.456Z")
                .level("WARN")
                .source("127.0.0.1")
                .serviceName("order-service")
                .message("数据库连接池使用率达到 85%，活跃连接数 170/200")
                .metadata(Map.of("thread", "db-pool-monitor", "activeConnections", "170", "maxConnections", "200", "usagePercent", "85"))
                .build());
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:25:00.789Z")
                .level("ERROR")
                .source("127.0.0.1")
                .serviceName("user-service")
                .message("RedisConnectionException: 无法连接到 Redis 集群，连接超时")
                .metadata(Map.of("thread", "redis-connection-pool-1", "traceId", "trace-ghi789",
                        "exception", "RedisConnectionException", "connectionTimeout", "3000ms"))
                .build());
        return logs;
    }

    private List<LogEntry> buildDatabaseSlowQueryLogs() {
        List<LogEntry> logs = new ArrayList<>();
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:20:00.000Z")
                .level("WARN")
                .source("127.0.0.1")
                .serviceName("order-service")
                .message("慢查询检测: SELECT 查询耗时 3.5s，超过阈值 1s")
                .metadata(Map.of("database", "order_db", "executionTime", "3.5s", "threshold", "1s",
                        "rowsExamined", "580000", "rowsReturned", "100", "useIndex", "idx_created_at"))
                .build());
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:21:30.000Z")
                .level("WARN")
                .source("127.0.0.1")
                .serviceName("user-service")
                .message("慢查询检测: UPDATE 语句耗时 2.1s，超过阈值 1s")
                .metadata(Map.of("database", "user_db", "executionTime", "2.1s", "threshold", "1s",
                        "rowsAffected", "45000", "useIndex", "none (全表扫描)"))
                .build());
        return logs;
    }

    private List<LogEntry> buildSystemEventLogs() {
        List<LogEntry> logs = new ArrayList<>();
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:00:00.000Z")
                .level("INFO")
                .source("ci-cd-system")
                .serviceName("payment-service")
                .message("服务部署完成: payment-service v2.3.1 -> v2.4.0")
                .metadata(Map.of("eventType", "DEPLOYMENT", "previousVersion", "v2.3.1",
                        "currentVersion", "v2.4.0", "deployedBy", "devops-bot", "deployStrategy", "rolling-update"))
                .build());
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:10:00.000Z")
                .level("INFO")
                .source("hpa-controller")
                .serviceName("order-service")
                .message("HPA 触发扩容: order-service 副本数 3 -> 5")
                .metadata(Map.of("eventType", "SCALE_UP", "previousReplicas", "3",
                        "currentReplicas", "5", "triggerReason", "CPU 使用率超过 70%"))
                .build());
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T09:50:00.000Z")
                .level("INFO")
                .source("config-center")
                .serviceName("user-service")
                .message("配置变更: user-service 数据库连接池参数调整")
                .metadata(Map.of("eventType", "CONFIG_CHANGE", "changedBy", "dba-team",
                        "configKey", "spring.datasource.hikari.maximum-pool-size", "oldValue", "50", "newValue", "100"))
                .build());
        return logs;
    }

    private List<LogEntry> filterLogs(List<LogEntry> logs, String query) {
        if (query == null || query.trim().isEmpty()) {
            return logs;
        }
        List<String> keywords = Arrays.asList(query.toLowerCase().split("\\s+"));
        return logs.stream()
                .filter(log -> {
                    String searchText = (log.getMessage() + " " + log.getServiceName() + " " +
                            log.getLevel() + " " + log.getSource() + " " +
                            (log.getMetadata() != null ? log.getMetadata().values().toString() : ""))
                            .toLowerCase();
                    return keywords.stream().allMatch(searchText::contains);
                })
                .collect(Collectors.toList());
    }

    // ========== 数据模型 ==========

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LogTopic {
        @JsonProperty("name")
        private String name;
        @JsonProperty("displayName")
        private String displayName;
        @JsonProperty("description")
        private String description;
        @JsonProperty("retentionDays")
        private Integer retentionDays;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LogQueryOutput {
        @JsonProperty("status")
        private String status;
        @JsonProperty("logTopic")
        private String logTopic;
        @JsonProperty("query")
        private String query;
        @JsonProperty("totalLogs")
        private int totalLogs;
        @JsonProperty("logs")
        private List<LogEntry> logs;
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
    public static class LogEntry {
        @JsonProperty("timestamp")
        private String timestamp;
        @JsonProperty("level")
        private String level;
        @JsonProperty("source")
        private String source;
        @JsonProperty("serviceName")
        private String serviceName;
        @JsonProperty("message")
        private String message;
        @JsonProperty("metadata")
        private Map<String, Object> metadata;
    }
}
