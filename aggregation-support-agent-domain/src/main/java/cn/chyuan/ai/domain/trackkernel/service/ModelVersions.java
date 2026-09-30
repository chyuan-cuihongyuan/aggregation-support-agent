package cn.chyuan.ai.domain.trackkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * model 阶段（工单 1111 EU6，mlflow 思想）。
 * 版本号按模型自增/None→Staging→Production→Archived 四阶段任意流转留痕/
 * 未知阶段拒绝/同模型同时至多一个 Production。
 */
public final class ModelVersions {

    /** 阶段 */
    public enum Stage { NONE, STAGING, PRODUCTION, ARCHIVED }

    /** 模型版本 */
    public static final class Version {
        private final String model;
        private final int version;
        private Stage stage = Stage.NONE;
        private final List<String> history = new ArrayList<>();

        Version(String model, int version) {
            this.model = model;
            this.version = version;
            history.add("NONE");
        }

        public String model() {
            return model;
        }

        public int version() {
            return version;
        }

        public Stage stage() {
            return stage;
        }

        public List<String> history() {
            return List.copyOf(history);
        }
    }

    private final Map<String, Map<Integer, Version>> byModel = new LinkedHashMap<>();

    /** 注册新版本：版本号按模型自增 */
    public Version register(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名不能为空");
        }
        Map<Integer, Version> versions = byModel.computeIfAbsent(model, ignored -> new LinkedHashMap<>());
        Version version = new Version(model, versions.size() + 1);
        versions.put(version.version(), version);
        return version;
    }

    /** 阶段流转：任意流转留痕；未知版本拒绝；Production 唯一（同模型已有 Production 且非本版本拒绝） */
    public void transition(String model, int version, Stage target) {
        Map<Integer, Version> versions = byModel.get(model);
        Version item = versions == null ? null : versions.get(version);
        if (item == null) {
            throw new IllegalArgumentException("未知模型版本拒绝: " + model + "/" + version);
        }
        if (target == Stage.PRODUCTION) {
            for (Version other : versions.values()) {
                if (other.stage() == Stage.PRODUCTION && other.version() != version) {
                    throw new IllegalStateException(
                            "同模型同时至多一个 Production 拒绝: " + model + " 持有 " + other.version());
                }
            }
        }
        item.stage = target;
        item.history.add(target.name());
    }

    public Stage stage(String model, int version) {
        Map<Integer, Version> versions = byModel.get(model);
        Version item = versions == null ? null : versions.get(version);
        if (item == null) {
            throw new IllegalArgumentException("未知模型版本拒绝: " + model + "/" + version);
        }
        return item.stage();
    }

    public Integer productionVersion(String model) {
        Map<Integer, Version> versions = byModel.get(model);
        if (versions == null) {
            return null;
        }
        for (Version version : versions.values()) {
            if (version.stage() == Stage.PRODUCTION) {
                return version.version();
            }
        }
        return null;
    }

    public List<String> history(String model, int version) {
        Map<Integer, Version> versions = byModel.get(model);
        Version item = versions == null ? null : versions.get(version);
        if (item == null) {
            throw new IllegalArgumentException("未知模型版本拒绝: " + model + "/" + version);
        }
        return item.history();
    }
}
