package cn.chyuan.ai.domain.desiredkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drift 漂移检测（工单 0676 CB6，terraform 思想）。
 * 现态 vs 上次 apply 状态漂移检测（字段级清单）/期望态覆盖策略裁定
 * （以配置为准——漂移后重新收敛为 update 动作）/无漂移空报告。
 */
public final class DriftDetector {

    /** 字段级漂移：资源/属性/上次应用值/现态观察值 */
    public record Drift(String resource, String attr, String applied, String observed) {
    }

    public record Report(List<Drift> drifts, List<String> convergenceActions) {

        public boolean hasDrift() {
            return !drifts.isEmpty();
        }
    }

    private DriftDetector() {
    }

    /** 漂移检测：lastApplied（上次 apply 后状态）vs observed（现态观察） */
    public static Report detect(StateManager.State lastApplied, Map<String, Map<String, String>> observed) {
        List<Drift> drifts = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : lastApplied.resources().entrySet()) {
            Map<String, String> now = observed.get(e.getKey());
            if (now == null) {
                drifts.add(new Drift(e.getKey(), "<resource>", "applied", "<missing>"));
                continue;
            }
            java.util.Set<String> keys = new java.util.TreeSet<>();
            keys.addAll(e.getValue().keySet());
            keys.addAll(now.keySet());
            for (String key : keys) {
                String applied = e.getValue().get(key);
                String current = now.get(key);
                if (!java.util.Objects.equals(applied, current)) {
                    drifts.add(new Drift(e.getKey(), key, applied, current));
                }
            }
        }
        for (String id : observed.keySet()) {
            if (!lastApplied.resources().containsKey(id)) {
                drifts.add(new Drift(id, "<resource>", "<absent>", "observed"));
            }
        }
        return new Report(List.copyOf(drifts), List.of());
    }

    /**
     * 以配置为准收敛：漂移后用期望配置对现态重新出计划（update 动作），
     * 交由调用方 apply——期望态覆盖策略（config wins）。
     */
    public static Report converge(ConfigModel.Config desired, Map<String, Map<String, String>> observed,
                                  java.util.Set<String> forceNewAttrs) {
        Planner.Plan plan = Planner.plan(desired, observed, forceNewAttrs);
        return new Report(List.of(), plan.display());
    }
}
