package cn.chyuan.ai.domain.searchkernel.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 同义词扩展（工单 0401 AW6）。
 * 单向（A→B 仅查询 A 时扩展）/双向（组内任一触发全组）同义词表 → 查询词扩展集；
 * 多跳层级可配、环防护（扩展集防重入）。表入参注入（热加载形态）。纯函数。
 */
public class SynonymExpander {

    /** 同义规则类型 */
    public enum Type {
        ONE_WAY, TWO_WAY
    }

    /** 同义规则 */
    public record Synonym(Type type, String from, List<String> tos) {
    }

    private final List<Synonym> synonyms;
    private final int maxDepth;

    public SynonymExpander(List<Synonym> synonyms, int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("扩展层级至少 1");
        }
        this.synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        this.maxDepth = maxDepth;
    }

    /**
     * 扩展：返回含原词的扩展集（不含扩展路径环）。ONE_WAY 只沿 from→tos 方向；TWO_WAY 双向可跳。
     */
    public Set<String> expand(String word) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        out.add(word);
        visiting.add(word);
        expand(word, out, visiting, 0);
        return out;
    }

    private void expand(String current, Set<String> out, Set<String> visiting, int depth) {
        if (depth >= maxDepth) {
            return;
        }
        for (Synonym synonym : synonyms) {
            String next = null;
            if (synonym.type() == Type.ONE_WAY && synonym.from().equals(current)) {
                next = null; // 多目标逐个展开
                for (String to : synonym.tos()) {
                    if (visiting.add(to)) {
                        out.add(to);
                        expand(to, out, visiting, depth + 1);
                        visiting.remove(to);
                    }
                }
            } else if (synonym.type() == Type.TWO_WAY
                    && (synonym.from().equals(current) || synonym.tos().contains(current))) {
                if (visiting.add(synonym.from())) {
                    out.add(synonym.from());
                    expand(synonym.from(), out, visiting, depth + 1);
                    visiting.remove(synonym.from());
                }
                for (String to : synonym.tos()) {
                    if (visiting.add(to)) {
                        out.add(to);
                        expand(to, out, visiting, depth + 1);
                        visiting.remove(to);
                    }
                }
            }
        }
    }
}
