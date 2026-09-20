package cn.chyuan.ai.domain.jobkernel.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * misfire 补偿（工单 0498 BH3，xxl-job/quartz misfire 思想）。
 * 错过触发检测窗口（触发点 + 检测时限 < now 判错过）/补偿策略三选
 * （FIRE_ONCE 立即补一次/SKIP 跳过/REPLAY 全量重放）/补偿触发幂等去重
 * （同一原触发点只补一次）。
 */
public class MisfireHandler {

    /** 补偿策略 */
    public enum Strategy {
        FIRE_ONCE, SKIP, REPLAY
    }

    /** 待补偿判定 */
    public record MissedTrigger(long scheduledAt) {
    }

    /** 补偿计划：应补的触发时刻列表（FIRE_ONCE 取首个，REPLAY 全量，SKIP 空） */
    public record CompensationPlan(List<Long> fireAtVirtualPoints, boolean fireNow) {
    }

    private final long detectWindowMillis;
    private final Set<Long> compensated = new HashSet<>();

    public MisfireHandler(long detectWindowMillis) {
        if (detectWindowMillis < 0) {
            throw new IllegalArgumentException("检测时限须 ≥ 0");
        }
        this.detectWindowMillis = detectWindowMillis;
    }

    /** 错过判定：now 超出 触发点+检测窗口 判错过 */
    public boolean isMissed(long scheduledAt, long nowMillis) {
        return scheduledAt + detectWindowMillis < nowMillis;
    }

    /** 过期触发筛选 */
    public List<MissedTrigger> detect(List<Long> scheduledPoints, long nowMillis) {
        List<MissedTrigger> missed = new ArrayList<>();
        for (long point : scheduledPoints) {
            if (isMissed(point, nowMillis)) {
                missed.add(new MissedTrigger(point));
            }
        }
        return missed;
    }

    /** 补偿计划 + 幂等去重（同原触发点重复申报只生效一次），返回本批实际补偿触发点 */
    public synchronized List<Long> compensate(List<MissedTrigger> missed, Strategy strategy) {
        List<Long> fired = new ArrayList<>();
        for (MissedTrigger trigger : missed) {
            if (strategy == Strategy.SKIP) {
                continue;
            }
            if (!compensated.add(trigger.scheduledAt())) {
                continue;
            }
            if (strategy == Strategy.FIRE_ONCE && !fired.isEmpty()) {
                continue;
            }
            fired.add(trigger.scheduledAt());
        }
        return fired;
    }

    public synchronized int compensatedCount() {
        return compensated.size();
    }
}
