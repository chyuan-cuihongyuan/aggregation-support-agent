package cn.chyuan.ai.domain.memory.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆去重合并纯函数（工单 0233 AE6，借鉴 Mem0 dedup）—
 * 词面 Jaccard ≥ 阈值（默认 0.8）判近似；合并策略：频次高者胜（频次同取时间新），
 * 文本取字符更长（信息更多）。写入前去重挂点用（开关默认关）。
 *
 * @author chyuan
 */
public final class MemoryDeduplicator {

    /** 记忆条目视图 */
    public record MemoryItem(String memoryId, String text, int accessCount, long updatedAt) {
    }

    /** 合并决策：保留项 + 被合并掉的项 */
    public record DedupResult(List<MemoryItem> kept, Map<String, String> mergedInto) {
    }

    private final double threshold;

    public MemoryDeduplicator(double threshold) {
        this.threshold = threshold <= 0 ? 0.8d : threshold;
    }

    public MemoryDeduplicator() {
        this(0.8d);
    }

    /**
     * 去重合并：按写入序扫描，与已保留项近似即合并；合并后保留项频次累加
     * （访问历史不丢）。
     */
    public DedupResult dedup(List<MemoryItem> items) {
        List<MemoryItem> kept = new ArrayList<>();
        Map<String, String> mergedInto = new LinkedHashMap<>();
        if (items == null) {
            return new DedupResult(kept, Map.of());
        }
        for (MemoryItem item : items) {
            MemoryItem similar = null;
            for (MemoryItem candidate : kept) {
                if (similarity(candidate.text(), item.text()) >= threshold) {
                    similar = candidate;
                    break;
                }
            }
            if (similar == null) {
                kept.add(item);
                continue;
            }
            // 合并：频次累加、时间取新、文本取更长，保留项保留原 id
            boolean oldWins = similar.accessCount() > item.accessCount()
                    || (similar.accessCount() == item.accessCount()
                    && similar.updatedAt() >= item.updatedAt());
            MemoryItem winner = oldWins ? similar : item;
            MemoryItem merged = new MemoryItem(winner.memoryId(),
                    winner.text().length() >= (oldWins ? item.text() : similar.text()).length()
                            ? winner.text() : (oldWins ? item.text() : similar.text()),
                    similar.accessCount() + item.accessCount(),
                    Math.max(similar.updatedAt(), item.updatedAt()));
            kept.set(kept.indexOf(similar), merged);
            String loser = oldWins ? item.memoryId() : similar.memoryId();
            mergedInto.put(loser, winner.memoryId());
        }
        return new DedupResult(List.copyOf(kept), Map.copyOf(mergedInto));
    }

    /** 词面 Jaccard 相似度（2-gram 集合；空串间为 0） */
    public static double similarity(String a, String b) {
        if (a == null || b == null || a.length() < 2 || b.length() < 2) {
            return 0.0d;
        }
        java.util.Set<String> gramsA = bigrams(a);
        java.util.Set<String> gramsB = bigrams(b);
        java.util.Set<String> union = new java.util.HashSet<>(gramsA);
        union.addAll(gramsB);
        if (union.isEmpty()) {
            return 0.0d;
        }
        java.util.Set<String> intersection = new java.util.HashSet<>(gramsA);
        intersection.retainAll(gramsB);
        return (double) intersection.size() / union.size();
    }

    private static java.util.Set<String> bigrams(String text) {
        java.util.Set<String> grams = new java.util.HashSet<>();
        for (int i = 0; i + 2 <= text.length(); i++) {
            grams.add(text.substring(i, i + 2));
        }
        return grams;
    }

    public double threshold() {
        return threshold;
    }
}
