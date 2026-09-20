package cn.chyuan.ai.domain.jobkernel.service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 触发计算（工单 0497 BH2，quartz Trigger 思想）。
 * 给定时刻求 cron 下次触发/批量预计算时间窗内全部触发点（有序去重）/
 * 时区偏移换算纯函数（UTC±offset 分钟）。
 */
public class TriggerCalculator {

    /** 下次触发：严格 > afterEpochMillis 的最早触发点 */
    public long nextTrigger(CronParser.CronExpression cron, long afterEpochMillis) {
        ZonedDateTime t = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(afterEpochMillis), ZoneOffset.UTC)
                .plusSeconds(1)
                .withNano(0);
        long limit = afterEpochMillis + 366L * 24 * 3600 * 1000;
        while (t.toInstant().toEpochMilli() <= limit) {
            if (!cron.months().contains(t.getMonthValue())) {
                t = t.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
                continue;
            }
            if (!dayMatches(cron, t)) {
                t = t.plusDays(1).withHour(0).withMinute(0).withSecond(0);
                continue;
            }
            if (!cron.hours().contains(t.getHour())) {
                t = t.plusHours(1).withMinute(0).withSecond(0);
                continue;
            }
            if (!cron.minutes().contains(t.getMinute())) {
                t = t.plusMinutes(1).withSecond(0);
                continue;
            }
            if (cron.seconds().contains(t.getSecond())) {
                return t.toInstant().toEpochMilli();
            }
            t = t.plusSeconds(1);
        }
        throw new IllegalStateException("一年内无触发点: " + cron.expression());
    }

    /** 批量预计算：时间窗 (from, to] 内全部触发点（升序去重） */
    public List<Long> nextTriggers(CronParser.CronExpression cron, long fromEpochMillis, long toEpochMillis, int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("max 须 > 0");
        }
        TreeSet<Long> points = new TreeSet<>();
        long cursor = fromEpochMillis;
        while (points.size() < max) {
            long next = nextTrigger(cron, cursor);
            if (next > toEpochMillis) {
                break;
            }
            points.add(next);
            cursor = next;
        }
        return new ArrayList<>(points);
    }

    /** 时区偏移换算：UTC 时刻 ± offsetMinutes（纯函数） */
    public long shiftZone(long epochMillis, int offsetMinutes) {
        return epochMillis + offsetMinutes * 60_000L;
    }

    /** 日域匹配：日/周均通配 → 每日；否则日或周命中其一（quartz 语义） */
    private boolean dayMatches(CronParser.CronExpression cron, ZonedDateTime t) {
        boolean domMatch = cron.dayOfMonthWildcard() || cron.daysOfMonth().contains(t.getDayOfMonth());
        int isoDow = t.getDayOfWeek().getValue() % 7 + 1;
        boolean dowMatch = cron.dayOfWeekWildcard() || cron.daysOfWeek().contains(isoDow);
        if (cron.dayOfMonthWildcard() && cron.dayOfWeekWildcard()) {
            return true;
        }
        if (cron.dayOfMonthWildcard()) {
            return dowMatch;
        }
        if (cron.dayOfWeekWildcard()) {
            return domMatch;
        }
        return domMatch || dowMatch;
    }
}
