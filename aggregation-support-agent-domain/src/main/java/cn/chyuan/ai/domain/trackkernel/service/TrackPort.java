package cn.chyuan.ai.domain.trackkernel.service;

import java.util.List;

/**
 * 实验追踪端口（工单 1113 EU8，mlflow 思想）。
 * experiment·run·log·stage 入口统一编排：实验归档/run 终态/metric 时序/参数标签/
 * artifact 血缘/model 阶段/搜索过滤组合管线；modelkernel+tuningkernel 形状只读联动
 * （形状键 tag·digest·keepAlive·study·trial·objective 对齐，不 import 两域）/
 * track-kernel.enabled 默认关（开启才改变行为）。
 */
public interface TrackPort {

    // —— 实验（EU1）——
    String createExperiment(String name);

    void archiveExperiment(String experimentId);

    List<String> experiments();

    // —— run 生命周期（EU2）——
    String createRun(String experimentId, String runName);

    void finishRun(String runId);

    void failRun(String runId);

    void killRun(String runId);

    String runStatus(String runId);

    // —— 度量·参数·标签·产物（EU3/EU4/EU5）——
    void logMetric(String runId, String key, double value, long step);

    List<String> metricHistory(String runId, String key);

    void logParam(String runId, String key, String value);

    void setTag(String runId, String key, String value);

    void deleteTag(String runId, String key);

    void logArtifact(String runId, String path, long bytes);

    List<String> artifacts(String runId, String prefix);

    // —— model 阶段（EU6）——
    int registerModelVersion(String model);

    void transition(String model, int version, String stage);

    String stage(String model, int version);

    // —— 搜索（EU7）——
    List<String> search(String experimentId, String statusEq, String paramKey, String paramValue,
            String metricKey, Double metricMin, String sortMetric, boolean ascending, int offset,
            int limit);

    // —— modelkernel+tuningkernel 形状只读联动（EU8）——
    List<String> lineageShape();

    static TrackPort inMemory() {
        return new TrackHub();
    }
}
