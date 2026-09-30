package cn.chyuan.ai.domain.trackkernel.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 参数与标签（工单 1109 EU4，mlflow 思想）。
 * 参数写后不可变（同值幂等/异值拒绝）/标签可覆盖/标签删除（未知拒绝）/按标签过滤。
 */
public final class Params {

    private final Map<String, Map<String, String>> params = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> tags = new LinkedHashMap<>();

    /** 记录参数：同值幂等，异值拒绝 */
    public void logParam(String runId, String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("参数名不能为空");
        }
        Map<String, String> map = params.computeIfAbsent(runId, ignored -> new LinkedHashMap<>());
        String existing = map.get(key);
        if (existing != null && !existing.equals(value)) {
            throw new IllegalStateException("参数不可变异值拒绝: " + key + " " + existing + " -> " + value);
        }
        map.put(key, value);
    }

    /** 设置标签：可覆盖 */
    public void setTag(String runId, String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("标签名不能为空");
        }
        tags.computeIfAbsent(runId, ignored -> new LinkedHashMap<>()).put(key, value);
    }

    /** 删除标签：未知拒绝 */
    public void deleteTag(String runId, String key) {
        Map<String, String> map = tags.get(runId);
        if (map == null || map.remove(key) == null) {
            throw new IllegalArgumentException("未知标签拒绝删除: " + key);
        }
    }

    public Map<String, String> params(String runId) {
        return Map.copyOf(params.getOrDefault(runId, Map.of()));
    }

    public Map<String, String> tags(String runId) {
        return Map.copyOf(tags.getOrDefault(runId, Map.of()));
    }

    /** 按标签过滤 run：key=value 全匹配 */
    public boolean hasTag(String runId, String key, String value) {
        Map<String, String> map = tags.get(runId);
        return map != null && value.equals(map.get(key));
    }

    public List<String> tagged(String key, String value) {
        List<String> runIds = new java.util.ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : tags.entrySet()) {
            if (value.equals(entry.getValue().get(key))) {
                runIds.add(entry.getKey());
            }
        }
        return runIds;
    }
}
