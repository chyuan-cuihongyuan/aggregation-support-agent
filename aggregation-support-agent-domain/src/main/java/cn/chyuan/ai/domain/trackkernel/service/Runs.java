package cn.chyuan.ai.domain.trackkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * run 生命周期（工单 1107 EU2，mlflow 思想）。
 * 创建挂实验/FINISHED·FAILED·KILLED 终态不可逆/终态后写拒绝/同实验多 run 并存。
 */
public final class Runs {

    /** run 状态 */
    public enum Status { RUNNING, FINISHED, FAILED, KILLED }

    /** run */
    public static final class Run {
        private final String id;
        private final String experimentId;
        private final String name;
        private Status status = Status.RUNNING;

        Run(String id, String experimentId, String name) {
            this.id = id;
            this.experimentId = experimentId;
            this.name = name == null ? "" : name;
        }

        public String id() {
            return id;
        }

        public String experimentId() {
            return experimentId;
        }

        public String name() {
            return name;
        }

        public Status status() {
            return status;
        }
    }

    private final Map<String, Run> runs = new LinkedHashMap<>();
    private long seq;

    /** 创建 run：实验必须存在（活跃性由 Experiments 校验后调用） */
    public Run create(String experimentId, String name) {
        if (experimentId == null || experimentId.isBlank()) {
            throw new IllegalArgumentException("实验不能为空");
        }
        Run run = new Run("run-" + (++seq), experimentId, name);
        runs.put(run.id(), run);
        return run;
    }

    /** 终态化：RUNNING → 目标终态；终态不可逆（再终态拒绝） */
    public void terminate(String runId, Status target) {
        Run run = require(runId);
        if (run.status() != Status.RUNNING) {
            throw new IllegalStateException("run 终态不可逆拒绝: " + runId + " " + run.status());
        }
        run.status = target;
    }

    /** 写前校验：终态后写拒绝 */
    public void assertWritable(String runId) {
        Run run = require(runId);
        if (run.status() != Status.RUNNING) {
            throw new IllegalStateException("终态后写拒绝: " + runId + " " + run.status());
        }
    }

    public Run require(String runId) {
        Run run = runs.get(runId);
        if (run == null) {
            throw new IllegalArgumentException("未知 run 拒绝: " + runId);
        }
        return run;
    }

    public List<Run> ofExperiment(String experimentId) {
        List<Run> result = new ArrayList<>();
        for (Run run : runs.values()) {
            if (run.experimentId().equals(experimentId)) {
                result.add(run);
            }
        }
        return result;
    }

    public Map<String, Status> snapshot() {
        Map<String, Status> statusMap = new LinkedHashMap<>();
        for (Run run : runs.values()) {
            statusMap.put(run.id(), run.status());
        }
        return statusMap;
    }
}
