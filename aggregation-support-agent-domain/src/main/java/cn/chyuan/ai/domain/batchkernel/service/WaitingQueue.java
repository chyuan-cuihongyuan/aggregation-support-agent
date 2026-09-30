package cn.chyuan.ai.domain.batchkernel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 等待队列准入（工单 1059 EO2，vllm 思想）。
 * FCFS 入队/token 预算不足等待（无候选可纳返回 null）/预算释放后按序补位/
 * 同刻提交 FIFO；抢占者重排队首（EO4）；老化提权后先于新请求（EO7，Aging 排序）。
 */
public final class WaitingQueue {

    private final List<Requests.Request> queue = new ArrayList<>();
    private final Aging aging;

    public WaitingQueue(Aging aging) {
        this.aging = aging;
    }

    /** FCFS 尾插 */
    public void enqueue(Requests.Request request) {
        queue.add(request);
    }

    /** 抢占者重排队首 */
    public void requeueFront(Requests.Request request) {
        queue.add(0, request);
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }

    public List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (Requests.Request request : queue) {
            ids.add(request.id());
        }
        return ids;
    }

    /**
     * 首适配准入：按（老化提权降序, 队列位置升序）排序后取首个 token 预算可纳的请求；
     * 无可纳候选（预算不足或队空）返回 null 等待。
     */
    public Requests.Request next(long availableTokens, long nowTick) {
        Requests.Request best = null;
        for (Requests.Request request : queue) {
            if (request.declaredTokens() > availableTokens) {
                continue;
            }
            if (best == null || order(request, best, nowTick) < 0) {
                best = request;
            }
        }
        if (best != null) {
            queue.remove(best);
        }
        return best;
    }

    /** 排序：提权等级降序，同等级按队列位置（FIFO） */
    private int order(Requests.Request left, Requests.Request right, long nowTick) {
        int leftBoost = aging.boost(left.arrivalTick(), nowTick);
        int rightBoost = aging.boost(right.arrivalTick(), nowTick);
        if (leftBoost != rightBoost) {
            return Integer.compare(rightBoost, leftBoost);
        }
        return Integer.compare(queue.indexOf(left), queue.indexOf(right));
    }
}
