package cn.chyuan.ai.domain.vectorkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 元数据过滤检索（工单 0439 BA5，qdrant filterable 思想）。
 * 标签谓词端口（等值/数值范围/合取）+ 前置过滤（候选集筛后搜索，保证结果数）
 * + 后置过滤（topK 大池再筛，可能不足 K）两策略 + 策略选择口径（过滤选择性高走前置）。
 */
public class FilteredVectorSearch {

    /** 标签点 */
    public record Tagged(String id, float[] vector, java.util.Map<String, Object> tags) {
    }

    /** 检索结果 */
    public record Scored(String id, double score) {
    }

    /** 谓词端口：标签点是否通过 */
    public interface TagPredicate extends Predicate<Tagged> {
    }

    /** 等值谓词 */
    public static TagPredicate equalsTag(String key, Object value) {
        return tagged -> value.equals(tagged.tags().get(key));
    }

    /** 数值范围谓词 [min, max] */
    public static TagPredicate rangeTag(String key, double min, double max) {
        return tagged -> {
            Object raw = tagged.tags().get(key);
            return raw instanceof Number number && number.doubleValue() >= min && number.doubleValue() <= max;
        };
    }

    /** 合取 */
    public static TagPredicate allOf(TagPredicate... predicates) {
        return tagged -> {
            for (TagPredicate predicate : predicates) {
                if (!predicate.test(tagged)) {
                    return false;
                }
            }
            return true;
        };
    }

    private final VectorMath math;

    public FilteredVectorSearch(VectorMath math) {
        this.math = math;
    }

    /** 前置过滤：先筛候选集再暴力/图搜索，结果全部满足过滤且数量=min(k, 合格数) */
    public List<Scored> preFilter(List<Tagged> points, float[] query, int k, TagPredicate predicate) {
        List<Scored> scored = new ArrayList<>();
        for (Tagged point : points) {
            if (predicate.test(point)) {
                scored.add(new Scored(point.id(), math.similarity(query, point.vector)));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.subList(0, Math.min(k, scored.size()));
    }

    /** 后置过滤：先 topK 大池（k×poolFactor）再筛，可能不足 k */
    public List<Scored> postFilter(List<Tagged> points, float[] query, int k,
                                   TagPredicate predicate, int poolFactor) {
        List<Scored> pool = new ArrayList<>();
        for (Tagged point : points) {
            pool.add(new Scored(point.id(), math.similarity(query, point.vector)));
        }
        pool.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Scored> out = new ArrayList<>();
        int poolSize = Math.min(pool.size(), Math.max(k, k * poolFactor));
        for (int i = 0; i < poolSize && out.size() < k; i++) {
            String id = pool.get(i).id();
            for (Tagged point : points) {
                if (point.id().equals(id) && predicate.test(point)) {
                    out.add(pool.get(i));
                    break;
                }
            }
        }
        return out;
    }

    /** 策略选择：过滤选择性（合格比例）低于阈值（过滤性强）走前置，否则后置 */
    public List<Scored> search(List<Tagged> points, float[] query, int k, TagPredicate predicate) {
        long passing = points.stream().filter(predicate).count();
        double selectivity = points.isEmpty() ? 1.0 : (double) passing / points.size();
        return selectivity < 0.3
                ? preFilter(points, query, k, predicate)
                : postFilter(points, query, k, predicate, 3);
    }
}
