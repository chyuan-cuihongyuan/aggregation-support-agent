package cn.chyuan.ai.domain.jobkernel.service;

import java.util.List;

/**
 * 调度端口+组合管线（工单 0503 BH8）。
 * JobPort（注册/调度/触发回调）+内存调度器假实现+时钟端口注入+
 * 注册→调度→触发回调组合管线/job-kernel.enabled 默认关（开启才改变行为）。
 */
public interface JobPort {

    /** 注册任务 */
    void register(String taskId, String cron, int shardIndex, int shardTotal);

    /** 调度推进：返回到期触发点（taskId → 触发时刻） */
    List<ScheduledFire> schedule(long nowMillis);

    /** 触发回调接口 */
    @FunctionalInterface
    interface FireCallback {
        void onFire(String taskId, long triggerAt);
    }

    /** 到期触发项 */
    record ScheduledFire(String taskId, long triggerAt) {
    }

    /** 内存假实现：CronParser + TriggerCalculator + 回调 */
    class InMemoryScheduler implements JobPort {

        private final CronParser parser = new CronParser();
        private final TriggerCalculator calculator = new TriggerCalculator();
        private final java.util.Map<String, CronParser.CronExpression> jobs = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, Long> nextTrigger = new java.util.HashMap<>();
        private FireCallback callback;

        /** 绑定触发回调（组合管线） */
        public synchronized void bind(FireCallback callback) {
            this.callback = callback;
        }

        @Override
        public synchronized void register(String taskId, String cron, int shardIndex, int shardTotal) {
            CronParser.CronExpression expression = parser.parse(cron);
            jobs.put(taskId, expression);
        }

        @Override
        public synchronized List<ScheduledFire> schedule(long nowMillis) {
            List<ScheduledFire> fires = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, CronParser.CronExpression> entry : jobs.entrySet()) {
                String taskId = entry.getKey();
                Long next = nextTrigger.get(taskId);
                if (next == null) {
                    next = calculator.nextTrigger(entry.getValue(), nowMillis - 1);
                    nextTrigger.put(taskId, next);
                }
                while (next <= nowMillis) {
                    fires.add(new ScheduledFire(taskId, next));
                    if (callback != null) {
                        callback.onFire(taskId, next);
                    }
                    next = calculator.nextTrigger(entry.getValue(), next);
                    nextTrigger.put(taskId, next);
                }
            }
            return fires;
        }

        public synchronized int jobCount() {
            return jobs.size();
        }
    }
}
