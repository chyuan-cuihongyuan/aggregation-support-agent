package cn.chyuan.ai.domain.batchkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求生命周期（工单 1058 EO1，vllm 思想）。
 * add 创建 WAITING/调度晋升 RUNNING/完成 COMPLETED/失败 FAILED/重复 id 拒绝；
 * 非法跃迁（非 RUNNING 完成或失败）拒绝；chargedTokens 为预算账目、completedTokens 为 decode 进度。
 */
public final class Requests {

    /** 请求状态机 */
    public enum State { WAITING, RUNNING, PREEMPTED, COMPLETED, FAILED }

    /** 调度请求：声明 token 预算/到达时刻/进入 RUNNING 时刻/账目与进度 */
    public static final class Request {
        private final String id;
        private final long declaredTokens;
        private final long arrivalTick;
        private final long seq;
        private State state = State.WAITING;
        private long entryTick = -1;
        private long chargedTokens;
        private long completedTokens;
        private int preemptions;

        Request(String id, long declaredTokens, long arrivalTick, long seq) {
            this.id = id;
            this.declaredTokens = declaredTokens;
            this.arrivalTick = arrivalTick;
            this.seq = seq;
        }

        public String id() {
            return id;
        }

        public long declaredTokens() {
            return declaredTokens;
        }

        public long arrivalTick() {
            return arrivalTick;
        }

        public long seq() {
            return seq;
        }

        public State state() {
            return state;
        }

        public long entryTick() {
            return entryTick;
        }

        public long chargedTokens() {
            return chargedTokens;
        }

        public long completedTokens() {
            return completedTokens;
        }

        public int preemptions() {
            return preemptions;
        }

        void charge(long tokens) {
            chargedTokens += tokens;
        }

        void advance(long tokens) {
            completedTokens += tokens;
        }

        void resetProgress() {
            chargedTokens = 0;
            completedTokens = 0;
        }
    }

    private final Map<String, Request> requests = new LinkedHashMap<>();
    private long seqCounter;

    /** 创建 WAITING 请求；空 id/非正 token/重复 id 拒绝 */
    public Request add(String id, long declaredTokens, long arrivalTick) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("请求 id 不能为空");
        }
        if (declaredTokens <= 0) {
            throw new IllegalArgumentException("声明 token 必须为正: " + declaredTokens);
        }
        if (requests.containsKey(id)) {
            throw new IllegalArgumentException("重复请求 id: " + id);
        }
        Request request = new Request(id, declaredTokens, arrivalTick, ++seqCounter);
        requests.put(id, request);
        return request;
    }

    public Request get(String id) {
        Request request = requests.get(id);
        if (request == null) {
            throw new IllegalArgumentException("未知请求 id: " + id);
        }
        return request;
    }

    /** 晋升 RUNNING：WAITING/PREEMPTED 可晋升，抢占后重入重算（账目与进度归零） */
    public void toRunning(String id, long tick) {
        Request request = get(id);
        if (request.state != State.WAITING && request.state != State.PREEMPTED) {
            throw new IllegalStateException("非法晋升跃迁: " + request.state + " -> RUNNING");
        }
        request.resetProgress();
        request.state = State.RUNNING;
        request.entryTick = tick;
    }

    /** 抢占：仅 RUNNING 可被抢占 */
    public void toPreempted(String id) {
        Request request = get(id);
        if (request.state != State.RUNNING) {
            throw new IllegalStateException("非法抢占跃迁: " + request.state + " -> PREEMPTED");
        }
        request.state = State.PREEMPTED;
        request.preemptions++;
        request.resetProgress();
    }

    /** 完成：仅 RUNNING 可完成 */
    public void toCompleted(String id) {
        Request request = get(id);
        if (request.state != State.RUNNING) {
            throw new IllegalStateException("非法完成跃迁: " + request.state + " -> COMPLETED");
        }
        request.state = State.COMPLETED;
    }

    /** 失败：仅 RUNNING 可失败 */
    public void toFailed(String id) {
        Request request = get(id);
        if (request.state != State.RUNNING) {
            throw new IllegalStateException("非法失败跃迁: " + request.state + " -> FAILED");
        }
        request.state = State.FAILED;
    }
}
