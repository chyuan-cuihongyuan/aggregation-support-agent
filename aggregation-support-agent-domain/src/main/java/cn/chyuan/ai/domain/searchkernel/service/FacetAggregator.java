package cn.chyuan.ai.domain.searchkernel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * facet 分布聚合（工单 0400 AW5）。
 * 结果集 × facet 字段清单 → 每字段值计数分布（计数降序、同数键序）+ 数值字段 stats（min/max/avg）。
 * 多字段独立聚合；空结果空分布。纯函数。
 */
public class FacetAggregator {

    /** 数值统计 */
    public record Stats(double min, double max, double avg) {
    }

    /** facet 聚合结果 */
    public record FacetResult(Map<String, Long> counts, Map<String, Stats> numericStats) {
    }

    /**
     * 聚合：facetFields 为分类字段清单，numericFields 为数值字段清单。
     */
    public FacetResult aggregate(List<Map<String, String>> documents, List<String> facetFields,
                                 Map<String, List<Double>> numericValues) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String field : facetFields == null ? List.<String>of() : facetFields) {
            Map<String, Long> perField = new LinkedHashMap<>();
            for (Map<String, String> doc : documents == null ? List.<Map<String,String>>of() : documents) {
                String value = doc.get(field);
                if (value != null) {
                    perField.merge(value, 1L, Long::sum);
                }
            }
            List<Map.Entry<String, Long>> entries = new ArrayList<>(perField.entrySet());
            entries.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                    .thenComparing(Map.Entry::getKey));
            for (Map.Entry<String, Long> entry : entries) {
                counts.put(field + ":" + entry.getKey(), entry.getValue());
            }
        }
        Map<String, Stats> stats = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : (numericValues == null ? Map.<String, List<Double>>of() : numericValues).entrySet()) {
            List<Double> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            stats.put(entry.getKey(), new Stats(min, max, Math.round(avg * 100) / 100.0));
        }
        return new FacetResult(counts, stats);
    }
}
