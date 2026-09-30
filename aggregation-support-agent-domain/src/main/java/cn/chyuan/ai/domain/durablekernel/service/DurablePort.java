package cn.chyuan.ai.domain.durablekernel.service;

import java.util.List;

/**
 * 持久执行端口（工单 1073 EP8，temporal 思想）。
 * start·signal·query·replay 入口统一编排：事件历史/确定性重放/signal 事件化/
 * query 只读组合管线；workflow 域 Checkpoint 形状只读联动（形状键与 Checkpoint
 * record 字段对齐，不 import workflow 域）/durable-kernel.enabled 默认关（开启才改变行为）。
 */
public interface DurablePort {

    // —— 启动与重放（EP1/EP2）——
    String start(String workflowId, String input, Replayer.WorkflowCode code);

    String replay(String workflowId, Replayer.WorkflowCode code);

    List<String> history(String workflowId);

    // —— signal 与 query（EP6）——
    void signal(String workflowId, String name, String payload);

    List<String> query(String workflowId);

    // —— workflow 域 Checkpoint 形状只读联动（EP8）——
    List<String> checkpointShape();

    static DurablePort inMemory(int maxSteps) {
        return new DurableHub(maxSteps);
    }
}
