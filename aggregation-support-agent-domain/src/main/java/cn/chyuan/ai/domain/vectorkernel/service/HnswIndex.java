package cn.chyuan.ai.domain.vectorkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HNSW 图索引（工单 0436 BA2 + 0437 BA3 + 0440 BA6，qdrant HNSW 思想）。
 * 插入建图：层级指数衰减分配（层级随机端口注入，测试确定性）、自旧入口逐层贪心下降、
 * 每层 ef 候选连 M 条双向边、邻居超 maxM 按距离剪枝；搜索：入口逐层下降 → 第 0 层 ef 束搜
 * → topK；upsert 同 id 覆盖重插幂等；delete 墓碑（图不可达+可恢复）。进程内纯函数内核。
 */
public class HnswIndex {

    /** 随机层级端口（测试注入确定性） */
    public interface LevelGenerator {

        int level();
    }

    /** 图内点 */
    static final class Node {
        final String id;
        final float[] vector;
        final int level;
        final Map<Integer, Set<String>> neighbors = new HashMap<>();
        boolean tombstoned;

        Node(String id, float[] vector, int level) {
            this.id = id;
            this.vector = vector;
            this.level = level;
        }
    }

    /** 搜索结果 */
    public record Scored(String id, double score) {
    }

    private final int maxConnections;
    private final int efConstruction;
    private final VectorMath math;
    private final LevelGenerator levels;
    private final Map<String, Node> nodes = new HashMap<>();
    private String entryPoint;
    private int topLayer = -1;

    public HnswIndex(int maxConnections, int efConstruction, VectorMath math, LevelGenerator levels) {
        if (maxConnections < 1 || efConstruction < 1) {
            throw new IllegalArgumentException("M 与 efConstruction 至少 1");
        }
        this.maxConnections = maxConnections;
        this.efConstruction = efConstruction;
        this.math = math;
        this.levels = levels;
    }

    /** 插入/覆盖（同 id 幂等重插）；返回是否新插入 */
    public synchronized boolean insert(String id, float[] vector) {
        Node existing = nodes.get(id);
        if (existing != null) {
            detach(existing);
            Node fresh = new Node(id, vector, existing.level);
            nodes.put(id, fresh);
            link(fresh);
            return false;
        }
        int level = Math.max(0, levels.level());
        Node node = new Node(id, vector, level);
        nodes.put(id, node);
        if (entryPoint == null) {
            entryPoint = id;
            topLayer = level;
            return true;
        }
        link(node);
        if (node.level > topLayer) {
            topLayer = node.level;
            entryPoint = id;
        }
        return true;
    }

    /** topK 搜索：ef 自动提升至 K */
    public synchronized List<Scored> search(float[] query, int k) {
        return search(query, k, Math.max(efConstruction, k));
    }

    /** topK 搜索（显式 ef） */
    public synchronized List<Scored> search(float[] query, int k, int ef) {
        if (entryPoint == null) {
            return List.of();
        }
        String current = entryPoint;
        for (int layer = topLayer; layer >= 1; layer--) {
            current = greedyDescend(query, current, layer);
        }
        List<Scored> beam = beamSearch(query, current, Math.max(ef, k), 0);
        List<Scored> out = new ArrayList<>();
        for (Scored scored : beam) {
            Node node = nodes.get(scored.id());
            if (node != null && !node.tombstoned) {
                out.add(scored);
                if (out.size() >= k) {
                    break;
                }
            }
        }
        return out;
    }

    /** 删除墓碑：图不可达，节点保留可恢复 */
    public synchronized void delete(String id) {
        Node node = nodes.get(id);
        if (node != null && !node.tombstoned) {
            node.tombstoned = true;
            detach(node);
        }
    }

    /** 恢复墓碑（重新接入图） */
    public synchronized void restore(String id) {
        Node node = nodes.get(id);
        if (node != null && node.tombstoned) {
            node.tombstoned = false;
            link(node);
        }
    }

    public synchronized int size() {
        return (int) nodes.values().stream().filter(n -> !n.tombstoned).count();
    }

    /** 快照导出：点 id 与第 0 层邻接（持久化面用） */
    public synchronized Map<String, List<String>> adjacency() {
        Map<String, List<String>> out = new HashMap<>();
        for (Node node : nodes.values()) {
            if (!node.tombstoned) {
                out.put(node.id, new ArrayList<>(node.neighbors.getOrDefault(0, Set.of())));
            }
        }
        return out;
    }

    // ── 内部 ──

    /** 将节点接入图：自旧入口逐层下降，每层 ef 候选连边 */
    private void link(Node node) {
        String entry = entryPoint;
        if (entry == null || entry.equals(node.id)) {
            return;
        }
        // 高于节点层：贪心下降（只读）
        for (int layer = topLayer; layer > node.level; layer--) {
            entry = greedyDescend(node.vector, entry, layer);
        }
        // 节点层到第 0 层：ef 候选双向连边 + 剪枝
        for (int layer = Math.min(node.level, topLayer); layer >= 0; layer--) {
            List<Scored> candidates = beamSearch(node.vector, entry, efConstruction, layer);
            int connected = 0;
            for (Scored candidate : candidates) {
                if (candidate.id().equals(node.id) || connected >= maxConnections) {
                    continue;
                }
                Node other = nodes.get(candidate.id());
                addEdge(node, other.id, layer);
                addEdge(other, node.id, layer);
                prune(other, layer);
                connected++;
                entry = other.id;
            }
        }
    }

    private void addEdge(Node from, String to, int layer) {
        if (from.id.equals(to)) {
            return;
        }
        from.neighbors.computeIfAbsent(layer, k -> new HashSet<>()).add(to);
    }

    /** 邻居超 maxM 按距离剪枝（保留最相似） */
    private void prune(Node node, int layer) {
        Set<String> neighbors = node.neighbors.get(layer);
        if (neighbors == null || neighbors.size() <= maxConnections) {
            return;
        }
        List<Scored> scored = new ArrayList<>();
        for (String neighbor : neighbors) {
            Node target = nodes.get(neighbor);
            if (target != null && !target.tombstoned) {
                scored.add(new Scored(neighbor, math.similarity(node.vector, target.vector)));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        neighbors.clear();
        for (int i = 0; i < Math.min(maxConnections, scored.size()); i++) {
            neighbors.add(scored.get(i).id());
        }
    }

    /** 层内贪心下降：邻居中相似度更高的单步移动直至不动 */
    private String greedyDescend(float[] query, String from, int layer) {
        String current = from;
        double currentScore = similarityTo(query, current);
        boolean improved = true;
        while (improved) {
            improved = false;
            for (String neighbor : neighborsOf(current, layer)) {
                double score = similarityTo(query, neighbor);
                if (score > currentScore) {
                    current = neighbor;
                    currentScore = score;
                    improved = true;
                }
            }
        }
        return current;
    }

    /** 层内 best-first 束搜：扩展至访问 ef 个节点，返回按相似度降序的访问集 */
    private List<Scored> beamSearch(float[] query, String entry, int ef, int layer) {
        List<Scored> visited = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Scored> frontier = new ArrayList<>();
        double entryScore = similarityTo(query, entry);
        if (Double.isNaN(entryScore)) {
            return visited;
        }
        frontier.add(new Scored(entry, entryScore));
        seen.add(entry);
        while (!frontier.isEmpty()) {
            frontier.sort((a, b) -> Double.compare(b.score(), a.score()));
            Scored best = frontier.remove(0);
            visited.add(best);
            if (visited.size() >= ef) {
                break;
            }
            for (String neighbor : neighborsOf(best.id(), layer)) {
                if (seen.add(neighbor)) {
                    frontier.add(new Scored(neighbor, similarityTo(query, neighbor)));
                }
            }
        }
        visited.sort((a, b) -> Double.compare(b.score(), a.score()));
        return visited;
    }

    private Set<String> neighborsOf(String id, int layer) {
        Node node = nodes.get(id);
        if (node == null) {
            return Set.of();
        }
        Set<String> neighbors = node.neighbors.getOrDefault(layer, Set.of());
        Set<String> alive = new HashSet<>();
        for (String neighbor : neighbors) {
            Node target = nodes.get(neighbor);
            if (target != null && !target.tombstoned) {
                alive.add(neighbor);
            }
        }
        return alive;
    }

    private double similarityTo(float[] query, String id) {
        Node node = nodes.get(id);
        if (node == null || node.tombstoned) {
            return Double.NaN;
        }
        return math.similarity(query, node.vector);
    }

    private void detach(Node node) {
        for (Set<String> neighbors : node.neighbors.values()) {
            for (String neighbor : neighbors) {
                Node other = nodes.get(neighbor);
                if (other != null) {
                    other.neighbors.values().forEach(set -> set.remove(node.id));
                }
            }
        }
        node.neighbors.clear();
    }
}
