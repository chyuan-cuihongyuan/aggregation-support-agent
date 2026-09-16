package cn.chyuan.ai.domain.crawler.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 广度调度队列（工单 0421 AY3，crawl4ai 调度思想）。
 * 深度分层 FIFO + 同域优先级（高者先出）+ 全局指纹去重；
 * 最大深度与总容量上限入队拒绝。出队序：深度升序 → 优先级降序 → 先入先出。
 */
public class BreadthScheduler {

    /** 队列条目 */
    public record Entry(String fingerprint, String url, String domain, int depth, int priority, long seq) {
    }

    private final int maxDepth;
    private final int capacity;
    private final Set<String> seen = new HashSet<>();
    private final Map<Integer, Map<Integer, Deque<Entry>>> layers = new HashMap<>();
    private long seq;
    private int size;

    public BreadthScheduler(int maxDepth, int capacity) {
        this.maxDepth = maxDepth;
        this.capacity = capacity;
    }

    /** 入队：重复指纹/超深度/超容量返回 false */
    public synchronized boolean enqueue(String fingerprint, String url, String domain, int depth, int priority) {
        if (fingerprint == null || depth < 0 || depth > maxDepth || size >= capacity) {
            return false;
        }
        if (!seen.add(fingerprint)) {
            return false;
        }
        layers.computeIfAbsent(depth, k -> new HashMap<>())
                .computeIfAbsent(priority, k -> new ArrayDeque<>())
                .add(new Entry(fingerprint, url, domain, depth, priority, seq++));
        size++;
        return true;
    }

    /** 出队：深度升序 → 优先级降序 → FIFO；空返回 null */
    public synchronized Entry poll() {
        var sortedDepths = layers.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (sortedDepths.isEmpty()) {
            return null;
        }
        int depth = sortedDepths.get(0);
        Map<Integer, Deque<Entry>> byPriority = layers.get(depth);
        Integer priority = byPriority.keySet().stream()
                .filter(p -> !byPriority.get(p).isEmpty())
                .max(Integer::compareTo)
                .orElse(null);
        if (priority == null) {
            return null;
        }
        Entry entry = byPriority.get(priority).poll();
        if (byPriority.get(priority).isEmpty()) {
            byPriority.remove(priority);
        }
        if (byPriority.isEmpty()) {
            layers.remove(depth);
        }
        size--;
        return entry;
    }

    /** 指纹是否已入过队 */
    public synchronized boolean seen(String fingerprint) {
        return seen.contains(fingerprint);
    }

    public synchronized int size() {
        return size;
    }
}
