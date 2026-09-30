package cn.chyuan.ai.domain.trackkernel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * run 搜索过滤（工单 1112 EU7，mlflow 思想）。
 * 按状态/参数条件/metric 阈值过滤/条件组合 AND/按 metric 排序分页。
 */
public final class RunsSearch {

    /** run 视图：状态 + 参数 + 最新度量 */
    public record RunView(String runId, String status, Map<String, String> params,
            Map<String, Double> latestMetrics) {
    }

    private RunsSearch() {
    }

    /** 搜索：条件取 AND；null 条件跳过；sortMetric 非空时按最新值排序（缺失排末尾）；offset/limit 分页 */
    public static List<RunView> search(List<RunView> runs, String statusEq, String paramKey,
            String paramValue, String metricKey, Double metricMin, String sortMetric, boolean ascending,
            int offset, int limit) {
        if (offset < 0 || limit <= 0) {
            throw new IllegalArgumentException("分页参数拒绝: offset=" + offset + " limit=" + limit);
        }
        List<RunView> filtered = new ArrayList<>();
        for (RunView run : runs) {
            if (statusEq != null && !statusEq.equals(run.status())) {
                continue;
            }
            if (paramKey != null && !paramValue.equals(run.params().get(paramKey))) {
                continue;
            }
            if (metricKey != null) {
                Double latest = run.latestMetrics().get(metricKey);
                if (latest == null || metricMin != null && latest < metricMin) {
                    continue;
                }
            }
            filtered.add(run);
        }
        if (sortMetric != null) {
            Comparator<RunView> byMetric = Comparator.comparing(
                    run -> run.latestMetrics().getOrDefault(sortMetric, Double.NEGATIVE_INFINITY));
            filtered.sort(ascending ? byMetric : byMetric.reversed());
        }
        int from = Math.min(offset, filtered.size());
        int to = Math.min(from + limit, filtered.size());
        return new ArrayList<>(filtered.subList(from, to));
    }

    /** 谓词组合：AND（预留扩展面） */
    public static <T> Predicate<T> andAll(List<Predicate<T>> predicates) {
        return item -> {
            for (Predicate<T> predicate : predicates) {
                if (!predicate.test(item)) {
                    return false;
                }
            }
            return true;
        };
    }
}
