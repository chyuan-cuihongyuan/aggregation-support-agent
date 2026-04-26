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
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CLS 日志查询工具 — 从云日志服务检索和分析日志，支持 Mock 模式
 * <p>
 * 在 AIOps 场景中，智能体需要查询和分析系统日志来辅助故障诊断：
 * <ul>
 *   <li>查询应用错误日志，定位异常堆栈</li>
 *   <li>检索数据库慢查询日志，分析性能瓶颈</li>
 *   <li>查看系统指标日志，了解资源使用趋势</li>
 *   <li>分析系统事件日志，排查变更和部署影响</li>
 * </ul>
 * <p>
 * 支持四个日志主题（Topic）：
 * <ul>
 *   <li>system-metrics — 系统指标日志（CPU、内存、磁盘等）</li>
 *   <li>application-logs — 应用运行日志（错误、警告、信息等）</li>
 *   <li>database-slow-query — 数据库慢查询日志</li>
 *   <li>system-events — 系统事件日志（部署、扩容、配置变更等）</li>
 * </ul>
 * <p>
 * 支持两种运行模式：
 * <ul>
 *   <li>Mock 模式：返回预定义的模拟日志数据，用于开发和测试</li>
 *   <li>真实模式：通过 CLS API 查询真实日志（待接入）</li>
 * </ul>
 * <p>
 * 迁移自 OnCall-Agent-java 项目，作为本地 MCP 工具注册为 Spring Bean。
 */
@Slf4j
@Service
public class QueryLogsTools {

    /** 是否启用 Mock 模式，为 true 时返回模拟日志数据而不调用真实 CLS */
    @Value("${cls.mock-enabled:true}")
    private boolean mockEnabled;

    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ========== 工具方法（@Tool 标注，供 AI 智能体调用） ==========

    /**
     * 获取可用的日志主题列表
     * <p>
     * 返回系统中所有可查询的日志主题，每个主题包含名称和描述信息，
     * 智能体可据此选择合适的日志主题进行查询。
     *
     * @return JSON 格式的日志主题列表
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
            return result;

        } catch (Exception e) {
            log.error("获取日志主题列表失败: {}", e.getMessage(), e);
            return "{\"error\":true,\"message\":\"获取日志主题列表失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 查询日志 — 根据条件从指定日志主题中检索日志
     * <p>
     * 智能体通过此工具查询特定日志主题的日志内容，支持按关键词过滤和限制返回条数。
     *
     * @param region   地域，例如 ap-guangzhou、ap-shanghai
     * @param logTopic 日志主题名称，需从 getAvailableLogTopics 获取
     * @param query    查询关键词，支持 Lucene 语法，例如 "ERROR AND payment"
     * @param limit    返回的最大日志条数
     * @return JSON 格式的日志查询结果
     */
    @Tool(description = "从指定日志主题中查询日志，支持按地域、主题、关键词过滤，返回匹配的日志条目")
    public String queryLogs(String region, String logTopic, String query, int limit) {
        log.info("工具调用: 查询日志, region={}, logTopic={}, query={}, limit={}, mockEnabled={}",
                region, logTopic, query, limit, mockEnabled);

        try {
            // Mock 模式：返回模拟日志数据
            if (mockEnabled) {
                LogQueryOutput mockOutput = buildMockLogs(logTopic, query, limit);
                String result = objectMapper.writeValueAsString(mockOutput);
                log.info("Mock 模式返回模拟日志数据, 共 {} 条日志", mockOutput.getLogs().size());
                return result;
            }

            // 真实模式：调用 CLS API 查询日志（待接入）
            // TODO: 接入真实 CLS API
            log.warn("真实 CLS API 尚未接入，请配置 cls.mock-enabled=true 使用 Mock 模式");
            LogQueryOutput output = LogQueryOutput.builder()
                    .status("error")
                    .errorMessage("真实 CLS API 尚未接入，请使用 Mock 模式")
                    .totalLogs(0)
                    .logs(new ArrayList<>())
                    .build();
            return objectMapper.writeValueAsString(output);

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

    // ========== 日志主题构建方法 ==========

    /**
     * 构建所有可用的日志主题
     *
     * @return 日志主题列表
     */
    private List<LogTopic> buildLogTopics() {
        List<LogTopic> topics = new ArrayList<>();

        // 系统指标日志主题
        topics.add(LogTopic.builder()
                .name("system-metrics")
                .displayName("系统指标日志")
                .description("包含 CPU 使用率、内存使用率、磁盘 I/O、网络流量等系统级指标日志")
                .retentionDays(30)
                .build());

        // 应用运行日志主题
        topics.add(LogTopic.builder()
                .name("application-logs")
                .displayName("应用运行日志")
                .description("包含应用的 INFO、WARN、ERROR 级别日志，异常堆栈信息等")
                .retentionDays(15)
                .build());

        // 数据库慢查询日志主题
        topics.add(LogTopic.builder()
                .name("database-slow-query")
                .displayName("数据库慢查询日志")
                .description("包含执行时间超过阈值的 SQL 查询日志，含完整 SQL 语句和执行计划")
                .retentionDays(30)
                .build());

        // 系统事件日志主题
        topics.add(LogTopic.builder()
                .name("system-events")
                .displayName("系统事件日志")
                .description("包含服务部署、扩缩容、配置变更、重启等系统级事件日志")
                .retentionDays(90)
                .build());

        return topics;
    }

    // ========== 模拟数据构建方法 ==========

    /**
     * 根据日志主题构建模拟日志数据
     *
     * @param logTopic 日志主题名称
     * @param query    查询关键词
     * @param limit    返回条数限制
     * @return 模拟日志查询输出
     */
    private LogQueryOutput buildMockLogs(String logTopic, String query, int limit) {
        List<LogEntry> allLogs = new ArrayList<>();

        // 根据日志主题生成对应的模拟日志
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
                // 未知主题，返回空结果
                log.warn("未知的日志主题: {}", logTopic);
                break;
        }

        // 如果有关键词，进行简单过滤（模拟检索）
        List<LogEntry> filteredLogs = filterLogs(allLogs, query);

        // 限制返回条数
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

    /**
     * 构建系统指标模拟日志
     * <p>
     * 包含 CPU、内存、磁盘等系统指标的模拟日志条目
     */
    private List<LogEntry> buildSystemMetricsLogs() {
        List<LogEntry> logs = new ArrayList<>();

        // CPU 使用率日志
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:22:15.000Z")
                .level("WARN")
                .source("10.0.1.15")
                .serviceName("payment-service")
                .message("CPU 使用率达到 92%，超过告警阈值 80%")
                .metadata(Map.of(
                        "metric", "cpu_usage_percent",
                        "value", "92",
                        "threshold", "80",
                        "duration", "15m"
                ))
                .build());

        // 内存使用率日志
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:23:30.000Z")
                .level("WARN")
                .source("10.0.2.22")
                .serviceName("order-service")
                .message("内存使用率达到 91%，超过告警阈值 85%")
                .metadata(Map.of(
                        "metric", "memory_usage_percent",
                        "value", "91",
                        "threshold", "85",
                        "duration", "10m"
                ))
                .build());

        // 磁盘 I/O 日志
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:24:00.000Z")
                .level("INFO")
                .source("10.0.1.15")
                .serviceName("payment-service")
                .message("磁盘 I/O 等待时间升高，iowait 达到 35%")
                .metadata(Map.of(
                        "metric", "disk_iowait_percent",
                        "value", "35",
                        "normal", "<10"
                ))
                .build());

        return logs;
    }

    /**
     * 构建应用运行模拟日志
     * <p>
     * 包含应用错误、警告等运行日志的模拟条目
     */
    private List<LogEntry> buildApplicationLogs() {
        List<LogEntry> logs = new ArrayList<>();

        // 支付服务错误日志
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:22:45.123Z")
                .level("ERROR")
                .source("10.0.1.15")
                .serviceName("payment-service")
                .message("PaymentProcessingException: 支付回调处理失败，第三方网关超时")
                .metadata(Map.of(
                        "thread", "payment-callback-3",
                        "traceId", "trace-abc123",
                        "spanId", "span-def456",
                        "exception", "PaymentProcessingException",
                        "stackTrace", "PaymentProcessingException: 第三方网关超时\n" +
                                "  at cn.chyuan.payment.callback.PaymentCallbackHandler.process(PaymentCallbackHandler.java:45)\n" +
                                "  at cn.chyuan.payment.callback.PaymentCallbackHandler.handle(PaymentCallbackHandler.java:28)\n" +
                                "  at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)"
                ))
                .build());

        // 订单服务警告日志
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:23:15.456Z")
                .level("WARN")
                .source("10.0.2.22")
                .serviceName("order-service")
                .message("数据库连接池使用率达到 85%，活跃连接数 170/200")
                .metadata(Map.of(
                        "thread", "db-pool-monitor",
                        "activeConnections", "170",
                        "maxConnections", "200",
                        "usagePercent", "85"
                ))
                .build());

        // 用户服务错误日志
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:25:00.789Z")
                .level("ERROR")
                .source("10.0.3.18")
                .serviceName("user-service")
                .message("RedisConnectionException: 无法连接到 Redis 集群，连接超时")
                .metadata(Map.of(
                        "thread", "redis-connection-pool-1",
                        "traceId", "trace-ghi789",
                        "exception", "RedisConnectionException",
                        "redisCluster", "redis-prod-east-1",
                        "connectionTimeout", "3000ms"
                ))
                .build());

        return logs;
    }

    /**
     * 构建数据库慢查询模拟日志
     * <p>
     * 包含慢 SQL 查询的模拟日志条目
     */
    private List<LogEntry> buildDatabaseSlowQueryLogs() {
        List<LogEntry> logs = new ArrayList<>();

        // 订单表慢查询
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:20:00.000Z")
                .level("WARN")
                .source("10.0.4.10")
                .serviceName("order-service")
                .message("慢查询检测: SELECT 查询耗时 3.5s，超过阈值 1s")
                .metadata(Map.of(
                        "database", "order_db",
                        "sql", "SELECT o.*, u.username, p.product_name FROM orders o " +
                                "LEFT JOIN users u ON o.user_id = u.id " +
                                "LEFT JOIN products p ON o.product_id = p.id " +
                                "WHERE o.created_at > '2025-06-01' AND o.status = 'PENDING' " +
                                "ORDER BY o.created_at DESC LIMIT 100",
                        "executionTime", "3.5s",
                        "threshold", "1s",
                        "rowsExamined", "580000",
                        "rowsReturned", "100",
                        "useIndex", "idx_created_at"
                ))
                .build());

        // 用户表慢查询
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:21:30.000Z")
                .level("WARN")
                .source("10.0.4.10")
                .serviceName("user-service")
                .message("慢查询检测: UPDATE 语句耗时 2.1s，超过阈值 1s")
                .metadata(Map.of(
                        "database", "user_db",
                        "sql", "UPDATE users SET last_login_at = NOW(), login_count = login_count + 1 " +
                                "WHERE email LIKE '%@example.com'",
                        "executionTime", "2.1s",
                        "threshold", "1s",
                        "rowsAffected", "45000",
                        "useIndex", "none (全表扫描)"
                ))
                .build());

        return logs;
    }

    /**
     * 构建系统事件模拟日志
     * <p>
     * 包含服务部署、扩缩容、配置变更等系统事件的模拟日志条目
     */
    private List<LogEntry> buildSystemEventLogs() {
        List<LogEntry> logs = new ArrayList<>();

        // 服务部署事件
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:00:00.000Z")
                .level("INFO")
                .source("ci-cd-system")
                .serviceName("payment-service")
                .message("服务部署完成: payment-service v2.3.1 -> v2.4.0")
                .metadata(Map.of(
                        "eventType", "DEPLOYMENT",
                        "previousVersion", "v2.3.1",
                        "currentVersion", "v2.4.0",
                        "deployedBy", "devops-bot",
                        "deployStrategy", "rolling-update"
                ))
                .build());

        // 扩容事件
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T10:10:00.000Z")
                .level("INFO")
                .source("hpa-controller")
                .serviceName("order-service")
                .message("HPA 触发扩容: order-service 副本数 3 -> 5")
                .metadata(Map.of(
                        "eventType", "SCALE_UP",
                        "previousReplicas", "3",
                        "currentReplicas", "5",
                        "triggerReason", "CPU 使用率超过 70%",
                        "hpaName", "order-service-hpa"
                ))
                .build());

        // 配置变更事件
        logs.add(LogEntry.builder()
                .timestamp("2025-06-15T09:50:00.000Z")
                .level("INFO")
                .source("config-center")
                .serviceName("user-service")
                .message("配置变更: user-service 数据库连接池参数调整")
                .metadata(Map.of(
                        "eventType", "CONFIG_CHANGE",
                        "changedBy", "dba-team",
                        "configKey", "spring.datasource.hikari.maximum-pool-size",
                        "oldValue", "50",
                        "newValue", "100",
                        "configSource", "nacos"
                ))
                .build());

        return logs;
    }

    /**
     * 根据查询关键词过滤日志条目
     * <p>
     * 模拟检索功能，对日志内容进行简单的关键词匹配过滤。
     * 支持多个关键词的 AND 逻辑组合。
     *
     * @param logs  全量日志列表
     * @param query 查询关键词，支持空格分隔的多关键词 AND 查询
     * @return 过滤后的日志列表
     */
    private List<LogEntry> filterLogs(List<LogEntry> logs, String query) {
        if (query == null || query.trim().isEmpty()) {
            return logs;
        }

        // 将查询按空格分割为多个关键词
        List<String> keywords = Arrays.asList(query.toLowerCase().split("\\s+"));

        return logs.stream()
                .filter(log -> {
                    // 在日志的所有文本字段中搜索关键词
                    String searchText = (log.getMessage() + " " +
                            log.getServiceName() + " " +
                            log.getLevel() + " " +
                            log.getSource() + " " +
                            (log.getMetadata() != null ? log.getMetadata().values().toString() : ""))
                            .toLowerCase();
                    // 所有关键词都必须匹配（AND 逻辑）
                    return keywords.stream().allMatch(searchText::contains);
                })
                .collect(Collectors.toList());
    }

    // ========== 内部数据模型类 ==========

    /**
     * 日志主题信息 — 描述一个可查询的日志主题
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LogTopic {

        /** 主题标识名称，用于查询时指定 */
        @JsonProperty("name")
        @JsonPropertyDescription("日志主题名称标识")
        private String name;

        /** 主题显示名称 */
        @JsonProperty("displayName")
        @JsonPropertyDescription("日志主题显示名称")
        private String displayName;

        /** 主题描述信息 */
        @JsonProperty("description")
        @JsonPropertyDescription("日志主题描述")
        private String description;

        /** 日志保留天数 */
        @JsonProperty("retentionDays")
        @JsonPropertyDescription("日志保留天数")
        private Integer retentionDays;
    }

    /**
     * 日志查询输出 — 封装日志查询的整体结果
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LogQueryOutput {

        /** 查询状态：success 或 error */
        @JsonProperty("status")
        @JsonPropertyDescription("查询状态")
        private String status;

        /** 查询的日志主题 */
        @JsonProperty("logTopic")
        @JsonPropertyDescription("查询的日志主题")
        private String logTopic;

        /** 查询关键词 */
        @JsonProperty("query")
        @JsonPropertyDescription("查询关键词")
        private String query;

        /** 返回的日志条数 */
        @JsonProperty("totalLogs")
        @JsonPropertyDescription("返回的日志条数")
        private int totalLogs;

        /** 日志条目列表 */
        @JsonProperty("logs")
        @JsonPropertyDescription("日志条目列表")
        private List<LogEntry> logs;

        /** 错误信息（仅在查询失败时返回） */
        @JsonProperty("errorMessage")
        @JsonPropertyDescription("错误信息")
        private String errorMessage;
    }

    /**
     * 日志条目 — 单条日志记录
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LogEntry {

        /** 日志时间戳（ISO 8601 格式） */
        @JsonProperty("timestamp")
        @JsonPropertyDescription("日志时间戳")
        private String timestamp;

        /** 日志级别：ERROR、WARN、INFO、DEBUG */
        @JsonProperty("level")
        @JsonPropertyDescription("日志级别")
        private String level;

        /** 日志来源地址 */
        @JsonProperty("source")
        @JsonPropertyDescription("日志来源地址")
        private String source;

        /** 关联服务名称 */
        @JsonProperty("serviceName")
        @JsonPropertyDescription("关联服务名称")
        private String serviceName;

        /** 日志内容 */
        @JsonProperty("message")
        @JsonPropertyDescription("日志内容")
        private String message;

        /** 附加元数据（包含指标值、异常堆栈、SQL 等详细信息） */
        @JsonProperty("metadata")
        @JsonPropertyDescription("附加元数据")
        private Map<String, Object> metadata;
    }
}
