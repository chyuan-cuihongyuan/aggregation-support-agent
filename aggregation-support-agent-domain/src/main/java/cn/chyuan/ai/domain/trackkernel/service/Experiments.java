package cn.chyuan.ai.domain.trackkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * experiment 实验（工单 1106 EU1，mlflow 思想）。
 * 创建/按名唯一重复拒绝/归档保留只读（历史可查）/归档下新 run 拒绝。
 */
public final class Experiments {

    /** 实验状态 */
    public enum State { ACTIVE, ARCHIVED }

    /** 实验 */
    public static final class Experiment {
        private final String id;
        private final String name;
        private State state = State.ACTIVE;

        Experiment(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public State state() {
            return state;
        }
    }

    private final Map<String, Experiment> byId = new LinkedHashMap<>();
    private final Map<String, String> idByName = new LinkedHashMap<>();
    private long seq;

    /** 创建：按名唯一 */
    public Experiment create(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("实验名不能为空");
        }
        if (idByName.containsKey(name)) {
            throw new IllegalArgumentException("实验名重复拒绝: " + name);
        }
        Experiment experiment = new Experiment("exp-" + (++seq), name);
        byId.put(experiment.id(), experiment);
        idByName.put(name, experiment.id());
        return experiment;
    }

    /** 归档：保留只读；重复归档拒绝 */
    public void archive(String experimentId) {
        Experiment experiment = require(experimentId);
        if (experiment.state() == State.ARCHIVED) {
            throw new IllegalStateException("重复归档拒绝: " + experimentId);
        }
        experiment.state = State.ARCHIVED;
    }

    /** 归档实验下新 run 拒绝 */
    public void assertActive(String experimentId) {
        Experiment experiment = require(experimentId);
        if (experiment.state() != State.ACTIVE) {
            throw new IllegalStateException("归档实验下新 run 拒绝: " + experimentId);
        }
    }

    public Experiment require(String experimentId) {
        Experiment experiment = byId.get(experimentId);
        if (experiment == null) {
            throw new IllegalArgumentException("未知实验拒绝: " + experimentId);
        }
        return experiment;
    }

    public List<String> list() {
        List<String> lines = new ArrayList<>();
        for (Experiment experiment : byId.values()) {
            lines.add(experiment.id() + ":" + experiment.name() + ":" + experiment.state());
        }
        return lines;
    }
}
