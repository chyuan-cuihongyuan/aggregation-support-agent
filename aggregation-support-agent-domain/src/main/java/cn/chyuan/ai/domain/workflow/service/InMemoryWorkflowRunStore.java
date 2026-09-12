package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.adapter.port.IWorkflowRunStore;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流运行历史内存实现（工单 0212 AB9）— 默认实现零依赖。
 *
 * @author chyuan
 */
public class InMemoryWorkflowRunStore implements IWorkflowRunStore {

    private final Map<String, WorkflowRunRecord> runs = new ConcurrentHashMap<>();

    @Override
    public void saveRun(WorkflowRunRecord run) {
        runs.put(run.runId(), run);
    }

    @Override
    public WorkflowRunRecord findRun(String runId) {
        return runId == null ? null : runs.get(runId);
    }

    @Override
    public List<WorkflowRunRecord> recentRuns(String workflowName, int limit) {
        return runs.values().stream()
                .filter(r -> workflowName == null || workflowName.isBlank()
                        || r.workflowName().equals(workflowName))
                .sorted(Comparator.comparingLong(WorkflowRunRecord::createdAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }
}
