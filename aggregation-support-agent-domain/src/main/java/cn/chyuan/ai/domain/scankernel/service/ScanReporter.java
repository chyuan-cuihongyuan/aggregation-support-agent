package cn.chyuan.ai.domain.scankernel.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 扫描报告（工单 0456 BC6）。
 * 发现清单按规则/文件/severity 三维聚合计数 + topN 文件与规则排序
 * （计数降序同级字典序）+ 四口径摘要（总数/新增/抑制/基线）+ 确定性 JSON 导出。纯函数。
 */
public class ScanReporter {

    /** topN：计数降序、同数按 key 字典序 */
    public List<Map.Entry<String, Integer>> topN(Map<String, Integer> counts, int n) {
        List<Map.Entry<String, Integer>> sorted = new java.util.ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> !a.getValue().equals(b.getValue())
                ? b.getValue() - a.getValue()
                : a.getKey().compareTo(b.getKey()));
        return sorted.subList(0, Math.min(n, sorted.size()));
    }

    /** 按维度聚合计数（dimension: rule/file/severity） */
    public Map<String, Integer> aggregate(List<SuppressionBaseline.Finding> findings, String dimension) {
        Map<String, Integer> counts = new TreeMap<>();
        for (SuppressionBaseline.Finding finding : findings) {
            String key = switch (dimension) {
                case "rule" -> finding.ruleId();
                case "file" -> finding.file();
                case "severity" -> finding.severity();
                default -> throw new IllegalArgumentException("未知维度: " + dimension);
            };
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    /** 四口径摘要 */
    public Map<String, Integer> summary(List<SuppressionBaseline.Finding> findings) {
        Map<String, Integer> out = new LinkedHashMap<>();
        int suppressed = 0;
        int baseline = 0;
        int open = 0;
        for (SuppressionBaseline.Finding finding : findings) {
            switch (finding.status()) {
                case SUPPRESSED -> suppressed++;
                case BASELINE -> baseline++;
                case OPEN -> open++;
            }
        }
        out.put("total", findings.size());
        out.put("open", open);
        out.put("suppressed", suppressed);
        out.put("baseline", baseline);
        return out;
    }

    /** 确定性 JSON 导出（键序固定，重放一致） */
    public String toJson(List<SuppressionBaseline.Finding> findings, long scannedAtMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"scannedAtMs\":").append(scannedAtMs);
        sb.append(",\"summary\":").append(mapToJson(summary(findings)));
        sb.append(",\"byRule\":").append(mapToJson(aggregate(findings, "rule")));
        sb.append(",\"byFile\":").append(mapToJson(aggregate(findings, "file")));
        sb.append(",\"bySeverity\":").append(mapToJson(aggregate(findings, "severity")));
        sb.append("}");
        return sb.toString();
    }

    private String mapToJson(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }
}
