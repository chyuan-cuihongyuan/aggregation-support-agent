package cn.chyuan.ai.domain.trackkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * metric 时序（工单 1108 EU3，mlflow 思想）。
 * log 追加含步长/同 key 多值成时序/NaN 拒绝/历史只读不可改。
 */
public final class Metrics {

    /** 度量点 */
    public record Point(String key, double value, long step) {
    }

    private final Map<String, List<Point>> series = new LinkedHashMap<>();

    /** 追加：同 run 同 key 多值成时序；NaN/Infinity 拒绝 */
    public void log(String runId, String key, double value, long step) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("度量名不能为空");
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("NaN/Infinity 度量拒绝: " + key);
        }
        series.computeIfAbsent(runId + "/" + key, ignored -> new ArrayList<>())
                .add(new Point(key, value, step));
    }

    /** 历史只读快照 */
    public List<Point> history(String runId, String key) {
        return List.copyOf(series.getOrDefault(runId + "/" + key, List.of()));
    }

    /** 最新值 */
    public Point latest(String runId, String key) {
        List<Point> points = series.get(runId + "/" + key);
        if (points == null || points.isEmpty()) {
            return null;
        }
        return points.get(points.size() - 1);
    }

    public List<String> keys(String runId) {
        List<String> keys = new ArrayList<>();
        for (String composite : series.keySet()) {
            if (composite.startsWith(runId + "/")) {
                keys.add(composite.substring(runId.length() + 1));
            }
        }
        return keys;
    }
}
