package cn.chyuan.ai.domain.trackkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实验追踪编排实现（工单 1113 EU8，mlflow 思想）。
 * 归档实验拒绝新 run；终态 run 拒绝写入；搜索组装 run 视图（参数 + 最新度量）。
 */
public final class TrackHub implements TrackPort {

    private final Experiments experiments = new Experiments();
    private final Runs runs = new Runs();
    private final Metrics metrics = new Metrics();
    private final Params params = new Params();
    private final Artifacts artifacts = new Artifacts();
    private final ModelVersions models = new ModelVersions();

    @Override
    public String createExperiment(String name) {
        return experiments.create(name).id();
    }

    @Override
    public void archiveExperiment(String experimentId) {
        experiments.archive(experimentId);
    }

    @Override
    public List<String> experiments() {
        return experiments.list();
    }

    @Override
    public String createRun(String experimentId, String runName) {
        experiments.assertActive(experimentId);
        return runs.create(experimentId, runName).id();
    }

    @Override
    public void finishRun(String runId) {
        runs.terminate(runId, Runs.Status.FINISHED);
    }

    @Override
    public void failRun(String runId) {
        runs.terminate(runId, Runs.Status.FAILED);
    }

    @Override
    public void killRun(String runId) {
        runs.terminate(runId, Runs.Status.KILLED);
    }

    @Override
    public String runStatus(String runId) {
        return runs.require(runId).status().name();
    }

    @Override
    public void logMetric(String runId, String key, double value, long step) {
        runs.assertWritable(runId);
        metrics.log(runId, key, value, step);
    }

    @Override
    public List<String> metricHistory(String runId, String key) {
        List<String> lines = new ArrayList<>();
        for (Metrics.Point point : metrics.history(runId, key)) {
            lines.add(point.step() + "=" + point.value());
        }
        return lines;
    }

    @Override
    public void logParam(String runId, String key, String value) {
        runs.assertWritable(runId);
        params.logParam(runId, key, value);
    }

    @Override
    public void setTag(String runId, String key, String value) {
        runs.assertWritable(runId);
        params.setTag(runId, key, value);
    }

    @Override
    public void deleteTag(String runId, String key) {
        runs.assertWritable(runId);
        params.deleteTag(runId, key);
    }

    @Override
    public void logArtifact(String runId, String path, long bytes) {
        runs.assertWritable(runId);
        artifacts.register(runId, path, bytes);
    }

    @Override
    public List<String> artifacts(String runId, String prefix) {
        List<String> paths = new ArrayList<>();
        for (Artifacts.Artifact artifact : artifacts.tree(runId, prefix)) {
            paths.add(artifact.path() + ":" + artifact.bytes() + "B");
        }
        return paths;
    }

    @Override
    public int registerModelVersion(String model) {
        return models.register(model).version();
    }

    @Override
    public void transition(String model, int version, String stage) {
        models.transition(model, version, ModelVersions.Stage.valueOf(stage));
    }

    @Override
    public String stage(String model, int version) {
        return models.stage(model, version).name();
    }

    @Override
    public List<String> search(String experimentId, String statusEq, String paramKey, String paramValue,
            String metricKey, Double metricMin, String sortMetric, boolean ascending, int offset,
            int limit) {
        List<RunsSearch.RunView> views = new ArrayList<>();
        for (Runs.Run run : runs.ofExperiment(experimentId)) {
            Map<String, Double> latest = new LinkedHashMap<>();
            for (String key : metrics.keys(run.id())) {
                Metrics.Point point = metrics.latest(run.id(), key);
                if (point != null) {
                    latest.put(key, point.value());
                }
            }
            views.add(new RunsSearch.RunView(run.id(), run.status().name(),
                    params.params(run.id()), latest));
        }
        List<String> lines = new ArrayList<>();
        for (RunsSearch.RunView view : RunsSearch.search(views, statusEq, paramKey, paramValue,
                metricKey, metricMin, sortMetric, ascending, offset, limit)) {
            lines.add(view.runId() + ":" + view.status());
        }
        return lines;
    }

    @Override
    public List<String> lineageShape() {
        return List.of("tag", "digest", "keepAlive", "study", "trial", "objective");
    }
}
