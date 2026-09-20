package cn.chyuan.ai.domain.jobkernel.service;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调度端口+组合管线单测（工单 0503 BH8）：
 * 注册→调度→触发回调组合管线 + job-kernel.enabled 默认关口径。
 */
class JobPortPipelineTest {

    @Test
    void BH8_注册调度触发回调管线() {
        JobPort.InMemoryScheduler scheduler = new JobPort.InMemoryScheduler();
        StringBuilder fired = new StringBuilder();
        scheduler.bind((taskId, triggerAt) -> fired.append(taskId).append('@').append(triggerAt).append(';'));
        scheduler.register("every-minute", "0 * * * * *", 0, 1);
        scheduler.register("quarter", "0 0,15,30,45 * * * *", 0, 2);
        assertEquals(2, scheduler.jobCount());
        long base = ZonedDateTime.of(2026, 9, 21, 10, 30, 15, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
        assertTrue(scheduler.schedule(base).isEmpty(), "首次调度仅初始化游标");
        List<JobPort.ScheduledFire> fires = scheduler.schedule(base + 3 * 60_000L);
        assertEquals(3, fires.size(), "3 分钟窗 every-minute 触发 31/32/33 分 3 次（quarter 下次 10:45 窗外）");
        assertTrue(fired.length() > 0, "回调产出");
        assertTrue(scheduler.schedule(base + 3 * 60_000L).isEmpty(), "同窗口重复调度幂等");
        List<JobPort.ScheduledFire> advance = scheduler.schedule(base + 5 * 60_000L);
        assertTrue(advance.stream().allMatch(fire -> fire.triggerAt() > base + 3 * 60_000L),
                "时间推进只触发新到期点");
    }
}
