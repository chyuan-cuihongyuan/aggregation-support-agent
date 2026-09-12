package cn.chyuan.ai.domain.workflow.adapter.port;

import cn.chyuan.ai.domain.workflow.service.WorkflowRunRecord;

import java.util.List;

/**
 * 工作流运行历史存储端口（工单 0212 AB9，借鉴 n8n executions）—
 * domain 只依赖端口；infrastructure 提供内存/MyBatis 实现。
 *
 * @author chyuan
 */
public interface IWorkflowRunStore {

    void saveRun(WorkflowRunRecord run);

    WorkflowRunRecord findRun(String runId);

    /** 按工作流名查最近运行（上限条数，时间倒序） */
    List<WorkflowRunRecord> recentRuns(String workflowName, int limit);
}
