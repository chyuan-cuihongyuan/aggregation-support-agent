package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具结果溢出守卫（spill guard）—— 借鉴 spring-ai-mount-buzhou Spill 的「显式溢出标记」语义
 * 在本仓基线上的最小落地：工具返回进入 LLM 上下文前施加三道围栏，超限不静默丢弃，
 * 而是截断 + 在数据内留下模型可见的 spillNote（模型因此知道数据不完整，可改查而非误判）。
 *
 * <ul>
 *   <li><b>字符预算</b>：序列化结果超过 {@code aiops.spill.max-chars} 时截断并追加标记；</li>
 *   <li><b>告警条数</b>：活动告警超过 {@code aiops.spill.max-alerts} 时保留前 N 条，
 *       totalAlerts 保持真实总数并附 spillNote；</li>
 *   <li><b>时间序列降采样</b>：单序列点数超过 {@code aiops.spill.max-timeseries-points} 时
 *       等距抽样并强制保留首尾点（趋势边缘信息不丢）。</li>
 * </ul>
 *
 * <p>与 buzhou 原实现的偏差：buzhou 按 token 预算分级（压缩→摘要→拒识），本实现按字符预算
 * 单级截断——样例从简，token 化预算属 C02 memory 主题的后续演进。</p>
 */
@Slf4j
@Component
public class ToolResultSpillGuard {

    private static final String TRUNCATION_MARK = "\n[spill] 结果超限已截断：原始 %d 字符，预算 %d 字符。数据可能不完整，如需完整数据请缩小查询范围或增大步长。";

    @Value("${aiops.spill.max-chars:6000}")
    private int maxChars;

    @Value("${aiops.spill.max-alerts:20}")
    private int maxAlerts;

    @Value("${aiops.spill.max-timeseries-points:120}")
    private int maxTimeseriesPoints;

    @Value("${aiops.spill.max-log-entries:50}")
    private int maxLogEntries;

    /**
     * 字符预算围栏：超限时截断并追加模型可见的溢出标记（不静默）。
     */
    public String bound(String json) {
        if (json == null || json.length() <= maxChars) {
            return json;
        }
        String mark = String.format(TRUNCATION_MARK, json.length(), maxChars);
        String bounded = json.substring(0, Math.max(0, maxChars - mark.length())) + mark;
        log.warn("工具结果触发 spill 截断: originalChars={}, budgetChars={}", json.length(), maxChars);
        return bounded;
    }

    /**
     * 告警条数围栏：保留前 maxAlerts 条，真实总数保留在 totalAlerts，附 spillNote。
     */
    public QueryMetricsTools.PrometheusAlertsOutput capAlerts(QueryMetricsTools.PrometheusAlertsOutput output) {
        if (output == null || output.getAlerts() == null || output.getAlerts().size() <= maxAlerts) {
            return output;
        }
        List<QueryMetricsTools.SimplifiedAlert> capped =
                new ArrayList<>(output.getAlerts().subList(0, maxAlerts));
        output.setAlerts(capped);
        output.setSpillNote(String.format("[spill] 活动告警共 %d 条，超出单次返回上限 %d 条，仅保留前 %d 条（含最高优先触发顺序）；如需其余告警请分批查询。", output.getTotalAlerts(), maxAlerts, maxAlerts));
        log.warn("告警结果触发 spill 条数围栏: totalAlerts={}, capped={}", output.getTotalAlerts(), maxAlerts);
        return output;
    }

    /**
     * 时间序列降采样围栏：等距抽样至 maxTimeseriesPoints，强制保留首尾点。
     */
    public void capTimeSeries(List<QueryMetricsTools.MetricResult> results) {
        if (results == null) {
            return;
        }
        for (QueryMetricsTools.MetricResult mr : results) {
            List<QueryMetricsTools.TimeSeriesPoint> ts = mr.getTimeSeries();
            if (ts == null || ts.size() <= maxTimeseriesPoints) {
                continue;
            }
            mr.setTimeSeries(downsample(ts, maxTimeseriesPoints));
            mr.setSpillNote(String.format("[spill] 原时间序列 %d 点，等距降采样至 %d 点（保留首尾）。",
                    ts.size(), maxTimeseriesPoints));
        }
    }

    /**
     * 日志条数围栏：保留前 maxLogEntries 条（Loki 已按时间倒序，保留最近），
     * totalLogs 保持真实总数并附 spillNote。
     */
    public QueryLogsTools.LogQueryOutput capLogs(QueryLogsTools.LogQueryOutput output) {
        if (output == null || output.getLogs() == null || output.getLogs().size() <= maxLogEntries) {
            return output;
        }
        List<QueryLogsTools.LogEntry> capped =
                new ArrayList<>(output.getLogs().subList(0, maxLogEntries));
        output.setLogs(capped);
        output.setSpillNote(String.format("[spill] 匹配日志共 %d 条，超出单次返回上限 %d 条，仅保留最近 %d 条（时间倒序）；如需更早日志请缩小时间范围或提高过滤精度。",
                output.getTotalLogs(), maxLogEntries, maxLogEntries));
        log.warn("日志结果触发 spill 条数围栏: totalLogs={}, capped={}", output.getTotalLogs(), maxLogEntries);
        return output;
    }

    /**
     * 等距抽样并保留首尾点：步长取 (n-1)/(m-1)，首尾强制入选，中间按等距索引取整。
     */
    public static List<QueryMetricsTools.TimeSeriesPoint> downsample(
            List<QueryMetricsTools.TimeSeriesPoint> points, int target) {
        List<QueryMetricsTools.TimeSeriesPoint> out = new ArrayList<>(target);
        out.add(points.get(0));
        double step = (points.size() - 1) / (double) (target - 1);
        for (int i = 1; i < target - 1; i++) {
            out.add(points.get((int) Math.round(i * step)));
        }
        out.add(points.get(points.size() - 1));
        return out;
    }
}
