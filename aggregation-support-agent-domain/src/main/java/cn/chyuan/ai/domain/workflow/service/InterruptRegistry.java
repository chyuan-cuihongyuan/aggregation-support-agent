package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人工中断注册表（工单 0207 AB4，借鉴 LangGraph interrupts）—
 * 执行到 INTERRUPT 节点时登记 token（关联 runId/节点/图/上下文快照/已完成集），
 * resume 消费 token 注入人工输入后续跑；错误 token 拒绝。
 *
 * @author chyuan
 */
public class InterruptRegistry {

    /** 挂起令牌信息 */
    public record PendingInterrupt(String token, String runId, String nodeId, WorkflowGraph graph,
            java.util.Set<String> completedNodeIds, Map<String, Object> contextSnapshot, long createdAt) {
    }

    private final Map<String, PendingInterrupt> pending = new ConcurrentHashMap<>();

    /** 登记挂起：返回一次性 token */
    public String register(String runId, String nodeId, WorkflowGraph graph,
            java.util.Set<String> completedNodeIds, Map<String, Object> contextSnapshot) {
        String token = UUID.randomUUID().toString();
        pending.put(token, new PendingInterrupt(token, runId, nodeId, graph,
                completedNodeIds, contextSnapshot, System.currentTimeMillis()));
        return token;
    }

    /** 查询（不移除） */
    public PendingInterrupt peek(String token) {
        return token == null ? null : pending.get(token);
    }

    /** 消费（resume 时取走并移除）；不存在返回 null */
    public PendingInterrupt consume(String token) {
        return token == null ? null : pending.remove(token);
    }

    /** 当前挂起数（观测） */
    public int size() {
        return pending.size();
    }
}
