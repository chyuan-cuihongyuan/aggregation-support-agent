package cn.chyuan.ai.domain.browser.service;

import cn.chyuan.ai.domain.browser.adapter.port.BrowserAuditPort;
import cn.chyuan.ai.domain.browser.model.valobj.TaskRunResultVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存浏览器审计实现（AQ7 默认实现：审计日志 + 截图存证占位）。
 */
public class InMemoryBrowserAudit implements BrowserAuditPort {

    private final Map<String, List<AuditEntry>> logs = new LinkedHashMap<>();
    private final Map<String, List<String>> evidences = new LinkedHashMap<>();

    @Override
    public synchronized void log(AuditEntry entry) {
        if (entry == null || entry.taskId() == null || entry.taskId().isBlank()) {
            throw new IllegalArgumentException("审计任务ID不能为空");
        }
        logs.computeIfAbsent(entry.taskId(), k -> new ArrayList<>()).add(entry);
    }

    @Override
    public synchronized void evidence(String taskId, int step, byte[] screenshot, String note) {
        evidences.computeIfAbsent(taskId, k -> new ArrayList<>())
                .add("step" + step + ":" + (screenshot == null ? 0 : screenshot.length)
                        + "B:" + (note == null ? "" : note));
    }

    @Override
    public synchronized List<AuditEntry> byTask(String taskId) {
        return List.copyOf(logs.getOrDefault(taskId, List.of()));
    }

    @Override
    public synchronized List<String> evidenceList(String taskId) {
        return List.copyOf(evidences.getOrDefault(taskId, List.of()));
    }

    /** 任务引擎结果 → 审计登记（集成辅助） */
    public void recordRun(TaskRunResultVO result) {
        for (TaskRunResultVO.StepRecordVO step : result.getSteps()) {
            log(new AuditEntry(result.getTaskId(), step.getIndex(), step.getActionSummary(),
                    "-", step.getStatus(), step.getCostMs()));
        }
    }
}
