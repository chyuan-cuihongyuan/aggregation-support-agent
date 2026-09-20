package cn.chyuan.ai.domain.textkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * facet 切面聚合（工单 0493 BG6，elasticsearch terms agg 思想）。
 * 字段值聚合计数/过滤下钻再聚合/计数确定性排序（计数降序、同频字典序）。
 */
public class FacetAggregator {

    /** 切面桶：字段值 + 计数 */
    public record Bucket(String value, long count) {
    }

    /** 文档切面值登记：docId → (字段 → 值) */
    private final Map<Integer, Map<String, String>> docFacets = new HashMap<>();

    /** 登记文档切面 */
    public synchronized void indexDoc(int docId, Map<String, String> facets) {
        docFacets.put(docId, new LinkedHashMap<>(facets));
    }

    /** 聚合：字段值计数（计数降序、同频字典序确定性） */
    public synchronized List<Bucket> aggregate(String field) {
        Map<String, Long> counts = countBy(field, docFacets.keySet());
        return sorted(counts);
    }

    /** 下钻聚合：先按过滤字段取文档子集，再对目标字段聚合 */
    public synchronized List<Bucket> drillDown(String filterField, String filterValue, String targetField) {
        Map<String, Long> counts = new HashMap<>();
        for (Map.Entry<Integer, Map<String, String>> entry : docFacets.entrySet()) {
            Map<String, String> facets = entry.getValue();
            if (filterValue.equals(facets.get(filterField))) {
                counts.merge(facets.getOrDefault(targetField, ""), 1L, Long::sum);
            }
        }
        return sorted(counts);
    }

    private Map<String, Long> countBy(String field, Iterable<Integer> docIds) {
        Map<String, Long> counts = new HashMap<>();
        for (Integer docId : docIds) {
            counts.merge(docFacets.get(docId).getOrDefault(field, ""), 1L, Long::sum);
        }
        return counts;
    }

    private List<Bucket> sorted(Map<String, Long> counts) {
        List<Bucket> buckets = new ArrayList<>(counts.size());
        counts.forEach((value, count) -> buckets.add(new Bucket(value, count)));
        buckets.sort(java.util.Comparator.comparingLong(Bucket::count).reversed()
                .thenComparing(Bucket::value));
        return List.copyOf(buckets);
    }
}
