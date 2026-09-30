package cn.chyuan.ai.domain.trackkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实验追踪内核测试（工单 1106-1113 EU1-EU8，mlflow 思想）。
 * 实验归档/run 终态/metric 时序/参数标签/artifact 血缘/model 阶段/搜索过滤/端口组合管线。
 */
class TrackKernelTest {

    @Test
    void experiments() {
        TrackPort port = TrackPort.inMemory();
        String experiment = port.createExperiment("nlp-tuning");
        assertTrue(experiment.startsWith("exp-"));
        assertThrows(IllegalArgumentException.class, () -> port.createExperiment("nlp-tuning"),
                "按名唯一重复拒绝");
        port.createExperiment("cv-tuning");
        assertEquals(2, port.experiments().size());
        port.archiveExperiment(experiment);
        assertEquals(1, port.experiments().stream().filter(line -> line.endsWith("ARCHIVED")).count(),
                "归档保留只读可查");
        assertThrows(IllegalStateException.class, () -> port.createRun(experiment, "late"),
                "归档下新 run 拒绝");
        assertThrows(IllegalStateException.class, () -> port.archiveExperiment(experiment),
                "重复归档拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.archiveExperiment("nope"));
    }

    @Test
    void runLifecycle() {
        Runs runs = new Runs();
        Runs.Run first = runs.create("exp-1", "a");
        Runs.Run second = runs.create("exp-1", "b");
        assertNotEquals(first.id(), second.id(), "同实验多 run 并存");
        assertEquals(Runs.Status.RUNNING, runs.require(first.id()).status());
        runs.terminate(first.id(), Runs.Status.FINISHED);
        assertEquals(Runs.Status.FINISHED, runs.require(first.id()).status());
        assertThrows(IllegalStateException.class,
                () -> runs.terminate(first.id(), Runs.Status.FAILED), "终态不可逆");
        assertThrows(IllegalStateException.class, () -> runs.assertWritable(first.id()),
                "终态后写拒绝");
        runs.terminate(second.id(), Runs.Status.KILLED);
        assertThrows(IllegalArgumentException.class, () -> runs.require("nope"));
    }

    @Test
    void metricSeries() {
        Metrics metrics = new Metrics();
        metrics.log("r1", "loss", 0.9, 0);
        metrics.log("r1", "loss", 0.5, 1);
        metrics.log("r1", "loss", 0.2, 2);
        assertEquals(3, metrics.history("r1", "loss").size(), "同 key 多值成时序");
        assertEquals(0.2, metrics.latest("r1", "loss").value());
        assertThrows(UnsupportedOperationException.class,
                () -> metrics.history("r1", "loss").add(new Metrics.Point("loss", 0.1, 3)),
                "历史只读不可改");
        assertThrows(IllegalArgumentException.class, () -> metrics.log("r1", "loss", Double.NaN, 3),
                "NaN 拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> metrics.log("r1", "acc", Double.POSITIVE_INFINITY, 0));
        assertTrue(metrics.history("r1", "acc").isEmpty(), "不同 key 独立");
    }

    @Test
    void paramsAndTags() {
        Params params = new Params();
        params.logParam("r1", "lr", "0.01");
        params.logParam("r1", "lr", "0.01");
        assertEquals(Map.of("lr", "0.01"), params.params("r1"), "同值幂等");
        assertThrows(IllegalStateException.class, () -> params.logParam("r1", "lr", "0.1"),
                "参数不可变异值拒绝");
        params.setTag("r1", "env", "dev");
        params.setTag("r1", "env", "prod");
        assertEquals(Map.of("env", "prod"), params.tags("r1"), "标签可覆盖");
        assertTrue(params.hasTag("r1", "env", "prod"));
        params.deleteTag("r1", "env");
        assertFalse(params.hasTag("r1", "env", "prod"));
        assertThrows(IllegalArgumentException.class, () -> params.deleteTag("r1", "env"),
                "未知标签删除拒绝");
        assertThrows(IllegalArgumentException.class, () -> params.logParam("r1", " ", "v"));
    }

    @Test
    void artifactLineage() {
        Artifacts artifacts = new Artifacts();
        artifacts.register("r1", "model/best.pt", 1024);
        artifacts.register("r1", "model/last.pt", 2048);
        artifacts.register("r1", "metrics.json", 32);
        assertThrows(IllegalStateException.class, () -> artifacts.register("r1", "model/best.pt", 8),
                "重复路径拒绝");
        assertThrows(IllegalArgumentException.class, () -> artifacts.register("r1", " ", 1));
        assertThrows(IllegalArgumentException.class, () -> artifacts.register("r1", "x", 0));
        assertEquals(3, artifacts.count("r1"));
        List<String> tree = artifacts.tree("r1", "model/").stream()
                .map(Artifacts.Artifact::path).toList();
        assertEquals(List.of("model/best.pt", "model/last.pt"), tree, "前缀树形字典序");
        assertTrue(artifacts.has("r1", "model/best.pt"));
        assertFalse(artifacts.has("r2", "model/best.pt"), "run 间隔离");
    }

    @Test
    void modelStages() {
        ModelVersions models = new ModelVersions();
        assertEquals(1, models.register("iris").version());
        int second = models.register("iris").version();
        assertEquals(2, second, "版本号按模型自增");
        models.register("other");
        models.transition("iris", 1, ModelVersions.Stage.STAGING);
        models.transition("iris", 1, ModelVersions.Stage.PRODUCTION);
        assertEquals(Integer.valueOf(1), models.productionVersion("iris"));
        assertThrows(IllegalStateException.class,
                () -> models.transition("iris", second, ModelVersions.Stage.PRODUCTION),
                "同模型同时至多一个 Production");
        assertEquals(List.of("NONE", "STAGING", "PRODUCTION"), models.history("iris", 1),
                "流转留痕");
        models.transition("iris", 1, ModelVersions.Stage.ARCHIVED);
        assertNull(models.productionVersion("iris"), "归档后无生产版本");
        models.transition("iris", second, ModelVersions.Stage.PRODUCTION);
        assertEquals(Integer.valueOf(second), models.productionVersion("iris"), "归档后另一版本可上生产");
        assertThrows(IllegalArgumentException.class,
                () -> models.transition("iris", 99, ModelVersions.Stage.STAGING));
    }

    @Test
    void searchFilter() {
        TrackPort port = TrackPort.inMemory();
        String experiment = port.createExperiment("sweep");
        String first = port.createRun(experiment, "r1");
        port.logParam(first, "lr", "0.1");
        port.logMetric(first, "acc", 0.8, 0);
        port.finishRun(first);
        String second = port.createRun(experiment, "r2");
        port.logParam(second, "lr", "0.01");
        port.logMetric(second, "acc", 0.95, 0);
        port.finishRun(second);
        String third = port.createRun(experiment, "r3");
        port.logParam(third, "lr", "0.1");
        port.logMetric(third, "acc", 0.6, 0);

        assertEquals(List.of(first + ":FINISHED", second + ":FINISHED"),
                port.search(experiment, "FINISHED", null, null, null, null, null, true, 0, 10),
                "按状态过滤");
        assertEquals(2,
                port.search(experiment, null, "lr", "0.1", null, null, null, true, 0, 10).size(),
                "按参数条件过滤");
        assertEquals(List.of(second),
                port.search(experiment, null, null, null, "acc", 0.9, null, true, 0, 10).stream()
                        .map(line -> line.split(":")[0]).toList(), "按 metric 阈值过滤");
        assertEquals(List.of(first),
                port.search(experiment, "FINISHED", "lr", "0.1", null, null, null, true, 0, 10)
                        .stream().map(line -> line.split(":")[0]).toList(), "条件组合 AND");
        assertEquals(List.of(second, first, third),
                port.search(experiment, null, null, null, null, null, "acc", false, 0, 10).stream()
                        .map(line -> line.split(":")[0]).toList(), "按 metric 降序");
        assertEquals(List.of(second),
                port.search(experiment, null, null, null, null, null, "acc", false, 0, 1).stream()
                        .map(line -> line.split(":")[0]).toList(), "limit 分页");
        assertEquals(List.of(first, third),
                port.search(experiment, null, null, null, null, null, "acc", false, 1, 2).stream()
                        .map(line -> line.split(":")[0]).toList(), "offset+limit 分页");
        assertThrows(IllegalArgumentException.class,
                () -> port.search(experiment, null, null, null, null, null, null, true, -1, 5));
    }

    @Test
    void trackPortPipeline() {
        TrackPort port = TrackPort.inMemory();
        String experiment = port.createExperiment("e2e");
        String run = port.createRun(experiment, "main");
        port.logParam(run, "depth", "4");
        port.setTag(run, "team", "aiops");
        port.logMetric(run, "f1", 0.7, 0);
        port.logMetric(run, "f1", 0.9, 1);
        assertEquals(List.of("0=0.7", "1=0.9"), port.metricHistory(run, "f1"));
        port.logArtifact(run, "outputs/report.md", 256);
        assertEquals(List.of("outputs/report.md:256B"), port.artifacts(run, "outputs/"));

        int version = port.registerModelVersion("churn");
        port.transition("churn", version, "PRODUCTION");
        assertEquals("PRODUCTION", port.stage("churn", version));
        assertThrows(IllegalArgumentException.class, () -> port.transition("churn", version, "NOPE"),
                "未知阶段拒绝");

        port.finishRun(run);
        assertEquals("FINISHED", port.runStatus(run));
        assertThrows(IllegalStateException.class, () -> port.logMetric(run, "f1", 1.0, 2),
                "终态后写拒绝");
        assertThrows(IllegalStateException.class, () -> port.logArtifact(run, "x", 1));
        assertThrows(IllegalStateException.class, () -> port.setTag(run, "t", "v"));
        assertEquals(1,
                port.search(experiment, "FINISHED", null, null, null, null, null, true, 0, 10).size());
        assertEquals(List.of("tag", "digest", "keepAlive", "study", "trial", "objective"),
                port.lineageShape(), "modelkernel+tuningkernel 形状只读联动");
    }
}
