package cn.chyuan.ai.domain.textkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 前缀补全（工单 0491 BG4，elasticsearch completion suggester 思想）。
 * 补全索引（小写规范化）/按文档频率排序 topN/大小写不敏感。
 */
public class PrefixCompleter {

    /** 补全建议：词 + 文档频率 */
    public record Suggestion(String term, int docFrequency) {
    }

    /** 词 → 文档集合（df = 集合大小） */
    private final Map<String, java.util.Set<Integer>> completionIndex = new HashMap<>();

    /** 登记补全词（大小写不敏感，同文档同词幂等） */
    public synchronized void register(String term, int docId) {
        completionIndex
                .computeIfAbsent(normalize(term), k -> new java.util.LinkedHashSet<>())
                .add(docId);
    }

    /** 前缀建议：df 降序、同频字典序，取 topN */
    public synchronized List<Suggestion> suggest(String prefix, int topN) {
        if (topN <= 0) {
            throw new IllegalArgumentException("topN 须 > 0");
        }
        String normalized = normalize(prefix);
        List<Suggestion> matched = new ArrayList<>();
        for (Map.Entry<String, java.util.Set<Integer>> entry : completionIndex.entrySet()) {
            if (entry.getKey().startsWith(normalized)) {
                matched.add(new Suggestion(entry.getKey(), entry.getValue().size()));
            }
        }
        matched.sort(java.util.Comparator.comparingInt(Suggestion::docFrequency).reversed()
                .thenComparing(Suggestion::term));
        return List.copyOf(matched.subList(0, Math.min(topN, matched.size())));
    }

    public synchronized int indexSize() {
        return completionIndex.size();
    }

    private String normalize(String term) {
        return term.toLowerCase(Locale.ROOT);
    }
}
