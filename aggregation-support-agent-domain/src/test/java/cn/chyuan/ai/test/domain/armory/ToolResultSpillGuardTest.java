package cn.chyuan.ai.test.domain.armory;

import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server.QueryLogsTools;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server.QueryMetricsTools;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server.ToolResultSpillGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spill 溢出守卫行为锁（SELFLOOP loop-08 / C01）：
 * 字符预算截断带显式标记、告警条数围栏保真实总数、时间序列降采样保首尾。
 */
class ToolResultSpillGuardTest {

    private ToolResultSpillGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ToolResultSpillGuard();
        ReflectionTestUtils.setField(guard, "maxChars", 100);
        ReflectionTestUtils.setField(guard, "maxAlerts", 3);
        ReflectionTestUtils.setField(guard, "maxTimeseriesPoints", 5);
        ReflectionTestUtils.setField(guard, "maxLogEntries", 4);
    }

    @Test
    void boundWithinBudgetReturnsUnchanged() {
        String json = "{\"status\":\"success\"}";
        assertThat(guard.bound(json)).isSameAs(json);
    }

    @Test
    void boundOverBudgetTruncatesWithExplicitMark() {
        String json = "x".repeat(500);
        String bounded = guard.bound(json);

        assertThat(bounded.length()).isLessThanOrEqualTo(100);
        assertThat(bounded).contains("[spill]");
        assertThat(bounded).contains("500");
        // 标记必须让模型知道原始规模与截断事实
        assertThat(bounded).contains("截断");
    }

    @Test
    void boundNullAndEmptySafe() {
        assertThat(guard.bound(null)).isNull();
        assertThat(guard.bound("")).isEmpty();
    }

    @Test
    void capAlertsUnderLimitUnchanged() {
        QueryMetricsTools.PrometheusAlertsOutput output = alertsOutput(3, 3);
        QueryMetricsTools.PrometheusAlertsOutput capped = guard.capAlerts(output);

        assertThat(capped.getAlerts()).hasSize(3);
        assertThat(capped.getSpillNote()).isNull();
    }

    @Test
    void capAlertsOverLimitKeepsTrueTotalAndNote() {
        QueryMetricsTools.PrometheusAlertsOutput output = alertsOutput(87, 87);
        QueryMetricsTools.PrometheusAlertsOutput capped = guard.capAlerts(output);

        assertThat(capped.getAlerts()).hasSize(3);
        assertThat(capped.getTotalAlerts()).isEqualTo(87);
        assertThat(capped.getSpillNote()).contains("87").contains("[spill]");
    }

    @Test
    void capTimeSeriesDownsamplesAndKeepsEndpoints() {
        List<QueryMetricsTools.MetricResult> results = new ArrayList<>();
        QueryMetricsTools.MetricResult mr = new QueryMetricsTools.MetricResult();
        mr.setMetric(java.util.Map.of("__name__", "node_cpu"));
        mr.setTimeSeries(points(21));
        results.add(mr);

        guard.capTimeSeries(results);

        List<QueryMetricsTools.TimeSeriesPoint> ts = results.get(0).getTimeSeries();
        assertThat(ts).hasSize(5);
        // 首尾必须保留（趋势边缘）
        assertThat(ts.get(0).getTimestamp()).isEqualTo("0");
        assertThat(ts.get(ts.size() - 1).getTimestamp()).isEqualTo("20");
        assertThat(results.get(0).getSpillNote()).contains("21").contains("5");
    }

    @Test
    void capTimeSeriesUnderLimitUnchanged() {
        List<QueryMetricsTools.MetricResult> results = new ArrayList<>();
        QueryMetricsTools.MetricResult mr = new QueryMetricsTools.MetricResult();
        mr.setTimeSeries(points(4));
        results.add(mr);

        guard.capTimeSeries(results);

        assertThat(results.get(0).getTimeSeries()).hasSize(4);
        assertThat(results.get(0).getSpillNote()).isNull();
    }

    @Test
    void downsampleIsMonotonicIndexSequence() {
        List<QueryMetricsTools.TimeSeriesPoint> out = ToolResultSpillGuard.downsample(points(100), 5);
        assertThat(out).hasSize(5);
        // 等距单调，无重复
        for (int i = 1; i < out.size(); i++) {
            int prev = Integer.parseInt(out.get(i - 1).getTimestamp());
            int cur = Integer.parseInt(out.get(i).getTimestamp());
            assertThat(cur).isGreaterThan(prev);
        }
    }

    @Test
    void capLogsOverLimitKeepsTrueTotalAndNote() {
        QueryLogsTools.LogQueryOutput output = logsOutput(132, 132);
        QueryLogsTools.LogQueryOutput capped = guard.capLogs(output);

        assertThat(capped.getLogs()).hasSize(4);
        assertThat(capped.getTotalLogs()).isEqualTo(132);
        assertThat(capped.getSpillNote()).contains("132").contains("[spill]").contains("最近");
    }

    @Test
    void capLogsUnderLimitUnchanged() {
        QueryLogsTools.LogQueryOutput output = logsOutput(3, 3);
        assertThat(guard.capLogs(output).getSpillNote()).isNull();
        assertThat(output.getLogs()).hasSize(3);
    }

    // ========== 构造器 ==========

    private QueryMetricsTools.PrometheusAlertsOutput alertsOutput(int total, int listSize) {
        List<QueryMetricsTools.SimplifiedAlert> alerts = new ArrayList<>();
        for (int i = 0; i < listSize; i++) {
            alerts.add(QueryMetricsTools.SimplifiedAlert.builder()
                    .alertName("Alert-" + i).state("firing").severity("warning")
                    .serviceName("svc-" + i).instance("127.0.0.1:9090")
                    .build());
        }
        return QueryMetricsTools.PrometheusAlertsOutput.builder()
                .status("success").totalAlerts(total).alerts(alerts)
                .build();
    }

    private List<QueryMetricsTools.TimeSeriesPoint> points(int n) {
        List<QueryMetricsTools.TimeSeriesPoint> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(QueryMetricsTools.TimeSeriesPoint.builder()
                    .timestamp(String.valueOf(i)).value(String.valueOf(i * 1.5))
                    .build());
        }
        return list;
    }

    private QueryLogsTools.LogQueryOutput logsOutput(int total, int listSize) {
        List<QueryLogsTools.LogEntry> logs = new ArrayList<>();
        for (int i = 0; i < listSize; i++) {
            QueryLogsTools.LogEntry entry = new QueryLogsTools.LogEntry();
            entry.setTimestamp("2026-09-12T03:00:" + String.format("%02d", i % 60) + "Z");
            entry.setMessage("log-line-" + i);
            entry.setLevel("ERROR");
            logs.add(entry);
        }
        return QueryLogsTools.LogQueryOutput.builder()
                .status("success").totalLogs(total).logs(logs)
                .build();
    }
}
