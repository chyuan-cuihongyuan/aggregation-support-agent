package cn.chyuan.ai.domain.jobkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务表登记器（工单 0502 BH7）。
 * 任务登记（任务 id/cron 表达式/分片参数/状态/上次下次触发/重试计数）与查询。
 * job-kernel.enabled 默认关。持久化面 = 第 32 表 job_task。
 */
public class JobRegistry {

    /** 任务状态 */
    public enum Status {
        RUNNING, PAUSED, DEAD
    }

    /** 登记行（对应 job_task 表行） */
    public record TaskRow(String taskId, String cron, int shardIndex, int shardTotal,
            Status status, long lastTriggerAt, long nextTriggerAt, int retryCount) {
    }

    private final Map<String, TaskRow> rows = new ConcurrentHashMap<>();

    /** 登记任务（同 id 覆盖） */
    public synchronized TaskRow register(String taskId, String cron, int shardIndex, int shardTotal,
            Status status, long lastTriggerAt, long nextTriggerAt, int retryCount) {
        TaskRow row = new TaskRow(taskId, cron, shardIndex, shardTotal, status,
                lastTriggerAt, nextTriggerAt, retryCount);
        rows.put(taskId, row);
        return row;
    }

    /** 重试计数推进（死亡置 DEAD 幂等） */
    public synchronized void bumpRetry(String taskId, int retryCount) {
        TaskRow row = rows.get(taskId);
        if (row == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        rows.put(taskId, new TaskRow(row.taskId(), row.cron(), row.shardIndex(), row.shardTotal(),
                row.status(), row.lastTriggerAt(), row.nextTriggerAt(), retryCount));
    }

    public synchronized void markDead(String taskId) {
        TaskRow row = rows.get(taskId);
        if (row != null && row.status() != Status.DEAD) {
            rows.put(taskId, new TaskRow(row.taskId(), row.cron(), row.shardIndex(), row.shardTotal(),
                    Status.DEAD, row.lastTriggerAt(), row.nextTriggerAt(), row.retryCount()));
        }
    }

    /** RUNNING 任务（taskId 字典序） */
    public synchronized List<TaskRow> runningTasks() {
        List<TaskRow> result = new ArrayList<>();
        rows.values().stream()
                .filter(row -> row.status() == Status.RUNNING)
                .sorted(java.util.Comparator.comparing(TaskRow::taskId))
                .forEach(result::add);
        return result;
    }

    public synchronized TaskRow get(String taskId) {
        return rows.get(taskId);
    }

    public synchronized int size() {
        return rows.size();
    }

    /** Map 引用（登记行快照用） */
    public synchronized Map<String, TaskRow> snapshot() {
        return Map.copyOf(rows);
    }
}
