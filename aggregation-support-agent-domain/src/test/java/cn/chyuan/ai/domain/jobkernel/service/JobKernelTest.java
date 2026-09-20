package cn.chyuan.ai.domain.jobkernel.service;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务调度内核 BH1-BH7 单测（工单 0496-0502）：
 * cron 解析/触发计算/misfire 补偿/分片广播/并发阻塞/重试退避/任务表登记。
 */
class JobKernelTest {

    @Test
    void BH1_cron解析与非法拒绝() {
        CronParser parser = new CronParser();
        CronParser.CronExpression everyFiveSeconds = parser.parse("*/5 * * * * *");
        assertEquals(12, everyFiveSeconds.seconds().size(), "*/5 秒域 12 个取值");
        assertEquals(60, everyFiveSeconds.minutes().size());
        CronParser.CronExpression complex = parser.parse("0 15,45 9-17 1-15/2 * ?");
        assertTrue(complex.dayOfWeekWildcard());
        assertEquals(2, complex.minutes().size());
        assertTrue(complex.hours().contains(9) && complex.hours().contains(17));
        assertTrue(complex.daysOfMonth().contains(1) && complex.daysOfMonth().contains(15));
        assertEquals(7, complex.daysOfWeek().size(), "? 周域通配为全域");
        CronParser.CronParseException bad = assertThrows(CronParser.CronParseException.class,
                () -> parser.parse("61 * * * * *"), "秒域越界");
        assertEquals("second", bad.field());
        assertThrows(CronParser.CronParseException.class, () -> parser.parse("0 0 0 * *"), "五域拒绝");
        assertThrows(CronParser.CronParseException.class, () -> parser.parse("0 0 0 X * *"), "非数字拒绝");
        assertThrows(CronParser.CronParseException.class, () -> parser.parse("0 0 0 * * 1-0"), "区间非法拒绝");
    }

    @Test
    void BH2_下次触发与批量预计算() {
        CronParser.CronExpression cron = new CronParser().parse("0 0 * * * *");
        TriggerCalculator calculator = new TriggerCalculator();
        long base = ZonedDateTime.of(2026, 9, 21, 10, 30, 15, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
        long next = calculator.nextTrigger(cron, base);
        assertEquals(11, hourOf(next), "下一整点 11 点");
        assertEquals(0, minuteOf(next));
        List<Long> window = calculator.nextTriggers(cron, base, base + 5 * 3600_000L, 10);
        assertEquals(5, window.size(), "5 小时窗 5 个整点");
        assertTrue(window.stream().sorted().distinct().toList().equals(window), "升序去重");
        assertEquals(-28_800_000L, calculator.shiftZone(0L, -480), "UTC-8h 时区换算纯函数");
    }

    private int hourOf(long epochMillis) {
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC).getHour();
    }

    private int minuteOf(long epochMillis) {
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC).getMinute();
    }

    @Test
    void BH3_misfire检测三策略与幂等() {
        MisfireHandler handler = new MisfireHandler(60_000L);
        assertTrue(handler.isMissed(1_000L, 1_061_001L), "超出检测窗口判错过");
        assertFalse(handler.isMissed(1_000L, 61_000L), "窗口内不判错过");
        List<MisfireHandler.MissedTrigger> missed = handler.detect(List.of(1_000L, 2_000L, 900_000L), 900_500L);
        assertEquals(2, missed.size(), "900s 触发点未到期不判错过");
        assertEquals(List.of(1_000L), handler.compensate(missed, MisfireHandler.Strategy.FIRE_ONCE),
                "FIRE_ONCE 只补一次");
        assertEquals(2, handler.compensatedCount(), "幂等去重后登记 2 个原触发点");
        assertEquals(List.of(), handler.compensate(missed, MisfireHandler.Strategy.FIRE_ONCE), "重复申报幂等");
        MisfireHandler skip = new MisfireHandler(60_000L);
        assertEquals(List.of(), skip.compensate(missed, MisfireHandler.Strategy.SKIP), "SKIP 全跳过");
        MisfireHandler replay = new MisfireHandler(60_000L);
        assertEquals(2, replay.compensate(missed, MisfireHandler.Strategy.REPLAY).size(), "REPLAY 全量");
    }

    @Test
    void BH4_分片路由与失败清单() {
        assertEquals(ShardRouter.ShardParam.class, ShardRouter.ShardParam.class);
        assertThrows(IllegalArgumentException.class, () -> new ShardRouter.ShardParam(3, 3), "index < total");
        assertThrows(IllegalArgumentException.class, () -> ShardRouter.routeItem(0, 0));
        assertEquals(1, ShardRouter.routeItem(7, 3));
        assertEquals(List.of(0, 3), ShardRouter.itemsOfShard(6, new ShardRouter.ShardParam(0, 3)), "0,3 归分片 0");
        assertEquals(List.of(2, 5), ShardRouter.itemsOfShard(6, new ShardRouter.ShardParam(2, 3)));
        ShardRouter.BroadcastResult ok = ShardRouter.aggregate(List.of(
                new ShardRouter.ShardResult(0, true, null),
                new ShardRouter.ShardResult(1, true, null)));
        assertTrue(ok.allSuccess());
        ShardRouter.BroadcastResult partial = ShardRouter.aggregate(List.of(
                new ShardRouter.ShardResult(0, true, null),
                new ShardRouter.ShardResult(1, false, "timeout")));
        assertFalse(partial.allSuccess());
        assertEquals(1, partial.failures().size());
        assertEquals(1, partial.failures().get(0).index());
    }

    @Test
    void BH5_并发互斥阻塞策略与超时() {
        long[] now = {0L};
        ConcurrencyGuard guard = new ConcurrencyGuard(() -> now[0], 300_000L);
        assertEquals(ConcurrencyGuard.Decision.RUN, guard.onTrigger("t1", ConcurrencyGuard.BlockStrategy.DISCARD, 0L));
        assertEquals(ConcurrencyGuard.Decision.DISCARDED,
                guard.onTrigger("t1", ConcurrencyGuard.BlockStrategy.DISCARD, 1L), "运行中丢弃");
        assertEquals(ConcurrencyGuard.Decision.QUEUED,
                guard.onTrigger("t1", ConcurrencyGuard.BlockStrategy.QUEUE, 2L), "运行中排队");
        assertFalse(guard.timedOut("t1"), "未超时");
        now[0] = 301_000L;
        assertTrue(guard.timedOut("t1"), "超时中断判定");
        assertFalse(guard.isRunning("t1"), "超时任务视为已死可重入");
        assertEquals(ConcurrencyGuard.Decision.RUN,
                guard.onTrigger("t3", ConcurrencyGuard.BlockStrategy.OVERRIDE, 3L), "空闲任务首次触发直接运行");
        assertEquals(ConcurrencyGuard.Decision.OVERRIDE_RUNNING,
                guard.onTrigger("t3", ConcurrencyGuard.BlockStrategy.OVERRIDE, 4L), "运行中覆盖重跑");
    }

    @Test
    void BH6_指数退避抖动与死信登记() {
        long[] now = {0L};
        RetryBackoff backoff = new RetryBackoff(1_000L, 3, 0.5, () -> now[0], () -> 0.5);
        assertEquals(1_000L, backoff.nextDelay("t", 1, "e1"), "base×2^0");
        assertEquals(2_000L, backoff.nextDelay("t", 2, "e2"), "base×2^1（抖动系数 0.5×(0)）");
        assertEquals(4_000L, backoff.nextDelay("t", 3, "e3"));
        assertEquals(-1L, backoff.nextDelay("t", 4, "e4"), "耗尽登记死信");
        assertEquals(1, backoff.deadLetters().size());
        assertEquals("t", backoff.deadLetters().get(0).taskId());
        assertEquals("e4", backoff.deadLetters().get(0).reason());
        assertEquals(0L, backoff.deadLetters().get(0).lastAttemptAt());
        RetryBackoff jitter = new RetryBackoff(1_000L, 1, 0.5, () -> 0L, () -> 1.0);
        assertEquals(1_500L, jitter.nextDelay("t2", 1, "e"), "抖动 +50%");
        assertThrows(IllegalArgumentException.class,
                () -> new RetryBackoff(1, 1, 1.5, () -> 0L, () -> 0.0), "jitter 越界拒绝");
    }

    @Test
    void BH7_任务表登记与状态() {
        JobRegistry registry = new JobRegistry();
        registry.register("clean", "0 0 3 * * *", 0, 1, JobRegistry.Status.RUNNING, 0L, 0L, 0);
        registry.register("report", "0 */10 * * * *", 1, 2, JobRegistry.Status.RUNNING, 0L, 0L, 2);
        registry.bumpRetry("report", 3);
        assertEquals(3, registry.get("report").retryCount());
        registry.markDead("report");
        registry.markDead("report");
        assertEquals(1, registry.runningTasks().size(), "DEAD 不在运行视图且重复置 DEAD 幂等");
        assertEquals(2, registry.snapshot().size());
    }
}
