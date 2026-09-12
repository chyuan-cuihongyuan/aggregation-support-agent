package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.workflow.adapter.port.IWorkflowRunStore;
import cn.chyuan.ai.domain.workflow.service.WorkflowRunRecord;
import cn.chyuan.ai.infrastructure.dao.IWorkflowRunDao;
import cn.chyuan.ai.infrastructure.dao.po.WorkflowRunPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 工作流运行仓储实现（工单 0212 AB9）：实现 {@link IWorkflowRunStore} 端口，
 * 经 MyBatis 落 workflow_run 表（节点明细以 JSON 文本列承载，第 14 表双方言 DDL）。
 *
 * @author chyuan
 */
@Repository
public class WorkflowRunRepository implements IWorkflowRunStore {

    @Resource
    private IWorkflowRunDao dao;

    @Override
    public void saveRun(WorkflowRunRecord run) {
        dao.insert(toPo(run));
    }

    @Override
    public WorkflowRunRecord findRun(String runId) {
        return toDomain(dao.queryByRunId(runId));
    }

    @Override
    public List<WorkflowRunRecord> recentRuns(String workflowName, int limit) {
        return dao.queryRecent(workflowName, limit).stream()
                .map(WorkflowRunRepository::toDomain).toList();
    }

    private static WorkflowRunPO toPo(WorkflowRunRecord run) {
        WorkflowRunPO po = new WorkflowRunPO();
        po.setRunId(run.runId());
        po.setWorkflowName(run.workflowName());
        po.setWorkflowVersion(run.workflowVersion());
        po.setTenantId(run.tenantId());
        po.setStatus(run.status());
        po.setFailedNodeId(run.failedNodeId());
        po.setError(run.error());
        po.setDurationMs(run.durationMs());
        po.setNodeRunsJson(run.nodeRunsJson());
        po.setCreateTime(new java.util.Date());
        return po;
    }

    private static WorkflowRunRecord toDomain(WorkflowRunPO po) {
        if (po == null) {
            return null;
        }
        List<WorkflowRunRecord.NodeRunEntry> nodes = new java.util.ArrayList<>();
        if (po.getNodeRunsJson() != null && !po.getNodeRunsJson().isBlank()) {
            try {
                com.alibaba.fastjson.JSONArray array =
                        com.alibaba.fastjson.JSON.parseArray(po.getNodeRunsJson());
                for (int i = 0; i < array.size(); i++) {
                    com.alibaba.fastjson.JSONObject n = array.getJSONObject(i);
                    nodes.add(new WorkflowRunRecord.NodeRunEntry(n.getString("nodeId"),
                            n.getString("status"), n.getIntValue("attempts"),
                            n.getLongValue("durationMs"), n.getString("error")));
                }
            } catch (Exception ignored) {
                // 坏 JSON 按空明细处理（查询路径不抛错）
            }
        }
        return new WorkflowRunRecord(po.getRunId(), po.getWorkflowName(),
                po.getWorkflowVersion() == null ? 0 : po.getWorkflowVersion(),
                po.getTenantId(), po.getStatus(), po.getFailedNodeId(), po.getError(),
                po.getDurationMs() == null ? 0 : po.getDurationMs(), nodes,
                po.getCreateTime() == null ? 0 : po.getCreateTime().getTime());
    }
}
