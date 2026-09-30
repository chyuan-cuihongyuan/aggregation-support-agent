package cn.chyuan.ai.domain.trackkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * artifact 血缘（工单 1110 EU5，mlflow 思想）。
 * 路径注册挂 run/重复路径拒绝/树形列举（前缀过滤 + 字典序）/run 间隔离。
 */
public final class Artifacts {

    /** 产物：路径 + 字节数 */
    public record Artifact(String path, long bytes) {
    }

    private final Map<String, Map<String, Artifact>> byRun = new LinkedHashMap<>();

    /** 注册：同 run 重复路径拒绝；非正字节数拒绝 */
    public void register(String runId, String path, long bytes) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("产物路径不能为空");
        }
        if (bytes <= 0) {
            throw new IllegalArgumentException("产物字节数必须为正: " + bytes);
        }
        Map<String, Artifact> artifacts = byRun.computeIfAbsent(runId, ignored -> new TreeMap<>());
        if (artifacts.containsKey(path)) {
            throw new IllegalStateException("重复产物路径拒绝: " + runId + " " + path);
        }
        artifacts.put(path, new Artifact(path, bytes));
    }

    /** 树形列举：前缀过滤 + 字典序 */
    public List<Artifact> tree(String runId, String prefix) {
        Map<String, Artifact> artifacts = byRun.getOrDefault(runId, Map.of());
        List<Artifact> result = new ArrayList<>();
        for (Artifact artifact : artifacts.values()) {
            if (prefix == null || prefix.isEmpty() || artifact.path().startsWith(prefix)) {
                result.add(artifact);
            }
        }
        return result;
    }

    /** run 间隔离：路径仅属于注册的 run */
    public boolean has(String runId, String path) {
        Map<String, Artifact> artifacts = byRun.get(runId);
        return artifacts != null && artifacts.containsKey(path);
    }

    public int count(String runId) {
        Map<String, Artifact> artifacts = byRun.get(runId);
        return artifacts == null ? 0 : artifacts.size();
    }
}
