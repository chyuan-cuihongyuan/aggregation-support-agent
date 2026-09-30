package cn.chyuan.ai.domain.durablekernel.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WFT 任务队列 sticky（工单 1068 EP3，temporal 思想）。
 * 任务入队/worker 认领执行（poll 建立粘性）/sticky 命中直投不再入队/
 * worker 失效其粘性任务回队（attempt+1，回队重放）。
 */
public final class TaskQueues {

    /** 任务：workflowId + 尝试次数（回队递增） */
    public record Task(String workflowId, int attempt) {
    }

    private final Deque<Task> queue = new ArrayDeque<>();
    private final Map<String, String> sticky = new HashMap<>();
    private final Map<String, Integer> attempts = new HashMap<>();

    /** 入队（attempt 沿用已有计数） */
    public void enqueue(String workflowId) {
        requireWorkflowId(workflowId);
        queue.addLast(new Task(workflowId, attempts.getOrDefault(workflowId, 1)));
    }

    /** worker 认领队首任务并建立粘性；空队返回 null */
    public Task poll(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId 不能为空");
        }
        Task task = queue.pollFirst();
        if (task == null) {
            return null;
        }
        sticky.put(task.workflowId(), workerId);
        return task;
    }

    /** 投递：sticky 命中直投返回 true（不排队）；未命中入队返回 false */
    public boolean deliver(String workflowId) {
        requireWorkflowId(workflowId);
        if (sticky.containsKey(workflowId)) {
            return true;
        }
        enqueue(workflowId);
        return false;
    }

    /** 完成任务解除粘性 */
    public void complete(String workflowId) {
        sticky.remove(workflowId);
        attempts.remove(workflowId);
    }

    /** worker 失效：其粘性任务回队（attempt+1 回队重放），返回回队数 */
    public int invalidate(String workerId) {
        List<String> affected = new ArrayList<>();
        for (Map.Entry<String, String> entry : sticky.entrySet()) {
            if (entry.getValue().equals(workerId)) {
                affected.add(entry.getKey());
            }
        }
        for (String workflowId : affected) {
            sticky.remove(workflowId);
            int attempt = attempts.getOrDefault(workflowId, 1) + 1;
            attempts.put(workflowId, attempt);
            queue.addLast(new Task(workflowId, attempt));
        }
        return affected.size();
    }

    public boolean isSticky(String workflowId, String workerId) {
        return workerId.equals(sticky.get(workflowId));
    }

    public int queued() {
        return queue.size();
    }

    public List<Task> snapshot() {
        return new ArrayList<>(queue);
    }

    private void requireWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId 不能为空");
        }
    }
}
