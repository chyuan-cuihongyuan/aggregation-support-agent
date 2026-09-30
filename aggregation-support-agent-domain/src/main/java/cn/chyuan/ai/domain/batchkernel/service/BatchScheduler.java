package cn.chyuan.ai.domain.batchkernel.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 推理批调度编排实现（工单 1065 EO8，vllm 思想）。
 * 迭代级调度：每 step 先按剩余预算与批上限准入（chunked 请求首步即走块），
 * 再对批内成员推进一个单位（分块请求耗下一块、decode 请求 +1 token），
 * 预算超限按最晚进入者抢占重算；完成后预算回收再分配。
 */
public final class BatchScheduler implements BatchPort {

    private final Requests requests = new Requests();
    private final WaitingQueue waiting;
    private final ContinuousBatch batch;
    private final ChunkedPrefill chunker;
    private final long tokenBudget;
    private final int maxBatchSize;
    private final Map<String, Deque<Long>> chunkPlans = new HashMap<>();
    private long usedTokens;
    private long tick;

    public BatchScheduler(long tokenBudget, int maxBatchSize, long chunkBudget, long agingThresholdTicks, int agingMaxBoost) {
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("token 预算必须为正: " + tokenBudget);
        }
        this.tokenBudget = tokenBudget;
        this.maxBatchSize = maxBatchSize;
        this.batch = new ContinuousBatch(maxBatchSize);
        this.chunker = new ChunkedPrefill(chunkBudget);
        this.waiting = new WaitingQueue(new Aging(agingThresholdTicks, agingMaxBoost));
    }

    @Override
    public void submit(String id, long declaredTokens) {
        Requests.Request request = requests.add(id, declaredTokens, tick);
        if (chunker.chunked(declaredTokens)) {
            chunkPlans.put(id, new ArrayDeque<>(chunker.plan(declaredTokens)));
        }
        waiting.enqueue(request);
    }

    @Override
    public String state(String id) {
        return requests.get(id).state().name();
    }

    @Override
    public void complete(String id) {
        Requests.Request request = requests.get(id);
        if (!batch.contains(id) || request.state() != Requests.State.RUNNING) {
            throw new IllegalStateException("仅批内 RUNNING 请求可完成: " + id);
        }
        requests.toCompleted(id);
        batch.evict(id);
        releaseCharge(request);
    }

    @Override
    public void fail(String id) {
        Requests.Request request = requests.get(id);
        if (!batch.contains(id) || request.state() != Requests.State.RUNNING) {
            throw new IllegalStateException("仅批内 RUNNING 请求可失败: " + id);
        }
        requests.toFailed(id);
        batch.evict(id);
        releaseCharge(request);
    }

    @Override
    public int step() {
        tick++;
        admitLoop();
        if (batch.isEmpty()) {
            return 0;
        }
        int stepped = 0;
        for (String id : batch.ids()) {
            Requests.Request request = requests.get(id);
            Deque<Long> chunks = chunkPlans.get(id);
            if (request.chargedTokens() == 0) {
                chargePrefill(request, chunks);
            } else if (chunks != null && !chunks.isEmpty()) {
                long take = chunks.pollFirst();
                usedTokens += take;
                request.charge(take);
            } else {
                usedTokens += 1;
                request.charge(1);
                request.advance(1);
            }
            stepped++;
        }
        preemptLoop();
        return stepped;
    }

    @Override
    public List<String> batch() {
        return batch.ids();
    }

    @Override
    public long usedTokens() {
        return usedTokens;
    }

    @Override
    public long tick() {
        return tick;
    }

    @Override
    public int preemptions(String id) {
        return requests.get(id).preemptions();
    }

    @Override
    public List<String> optionsShape() {
        return List.of("prompt", "maxTokens", "temperature", "topK", "topP");
    }

    /** 首步 prefill：分块请求耗首块，非分块请求一次性计满声明 token */
    private void chargePrefill(Requests.Request request, Deque<Long> chunks) {
        if (chunks != null && !chunks.isEmpty()) {
            long take = chunks.pollFirst();
            usedTokens += take;
            request.charge(take);
        } else {
            usedTokens += request.declaredTokens();
            request.charge(request.declaredTokens());
        }
    }

    /** 准入循环：预算（含同批新纳请求的初始账目预留）与批上限内持续从等待队列取可纳请求 */
    private void admitLoop() {
        long reserved = 0;
        while (!waiting.isEmpty() && batch.size() < maxBatchSize) {
            Requests.Request next = waiting.next(tokenBudget - usedTokens - reserved, tick);
            if (next == null) {
                break;
            }
            Deque<Long> chunks = chunkPlans.get(next.id());
            reserved += chunks != null && !chunks.isEmpty() ? chunks.peekFirst() : next.declaredTokens();
            batch.admit(next.id());
            requests.toRunning(next.id(), tick);
        }
    }

    /** 抢占循环：预算超限逐出最晚进入的 RUNNING 者重排队首重算 */
    private void preemptLoop() {
        while (usedTokens > tokenBudget) {
            List<Requests.Request> members = new ArrayList<>();
            for (String id : batch.ids()) {
                members.add(requests.get(id));
            }
            Requests.Request victim = Preemptors.victim(members);
            if (victim == null) {
                break;
            }
            releaseCharge(victim);
            requests.toPreempted(victim.id());
            batch.evict(victim.id());
            if (chunker.chunked(victim.declaredTokens())) {
                chunkPlans.put(victim.id(), new ArrayDeque<>(chunker.plan(victim.declaredTokens())));
            }
            waiting.requeueFront(victim);
        }
    }

    /** 释放请求账目（完成/失败/抢占共用），预算回收待下轮准入再分配 */
    private void releaseCharge(Requests.Request request) {
        usedTokens -= request.chargedTokens();
        request.resetProgress();
        chunkPlans.remove(request.id());
    }
}
