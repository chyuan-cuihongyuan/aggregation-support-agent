package cn.chyuan.ai.domain.batchkernel.service;

import java.util.Comparator;
import java.util.List;

/**
 * 抢占选择（工单 1061 EO4，vllm 思想）。
 * 预算超限逐出最晚进入 RUNNING 者转 PREEMPTED/完成或失败请求不可抢占（仅 RUNNING 候选）/无候选返回 null。
 */
public final class Preemptors {

    private Preemptors() {
    }

    /** 牺牲者 = 候选中 entryTick 最大（最晚进入）的 RUNNING 请求；同刻进入取最晚提交（seq 大）者 */
    public static Requests.Request victim(List<Requests.Request> candidates) {
        return candidates.stream()
                .filter(request -> request.state() == Requests.State.RUNNING)
                .max(Comparator.comparingLong(Requests.Request::entryTick)
                        .thenComparingLong(Requests.Request::seq))
                .orElse(null);
    }
}
