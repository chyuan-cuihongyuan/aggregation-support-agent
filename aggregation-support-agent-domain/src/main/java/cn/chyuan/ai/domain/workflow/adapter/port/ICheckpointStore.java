package cn.chyuan.ai.domain.workflow.adapter.port;

import cn.chyuan.ai.domain.workflow.service.Checkpoint;

/**
 * 工作流检查点存储端口（工单 0206 AB3，借鉴 LangGraph checkpointer/Temporal durable execution）—
 * domain 只依赖本端口；infrastructure 提供内存/PG 实现。
 *
 * @author chyuan
 */
public interface ICheckpointStore {

    /** 保存（同 runId + seq 覆盖写不发生：seq 单调递增，重复保存按实现幂等） */
    void save(Checkpoint checkpoint);

    /** 取 run 最新检查点（无则 null） */
    Checkpoint findLatest(String runId);
}
