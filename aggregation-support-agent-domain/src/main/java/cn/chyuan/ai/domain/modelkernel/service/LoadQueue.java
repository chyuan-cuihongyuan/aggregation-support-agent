package cn.chyuan.ai.domain.modelkernel.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 加载队列（工单 1033 EL5，ollama 并发加载思想）。
 * 并发请求排队 FIFO/容量超限拒绝/加载完成出队。
 */
public final class LoadQueue {

    /** 加载请求票据 */
    public record Request(String model, String requester) {

        public Request {
            if (model == null || model.isEmpty()) {
                throw new IllegalArgumentException("加载模型为空");
            }
            if (requester == null || requester.isEmpty()) {
                throw new IllegalArgumentException("请求方为空");
            }
        }
    }

    private final int capacity;
    private final Deque<Request> queue = new ArrayDeque<>();

    public LoadQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("队列容量必须为正: " + capacity);
        }
        this.capacity = capacity;
    }

    /** 入队：FIFO；容量超限拒绝（背压） */
    public synchronized Request enqueue(String model, String requester) {
        Request request = new Request(model, requester);
        if (queue.size() >= capacity) {
            throw new IllegalStateException("加载队列已满: " + capacity);
        }
        queue.addLast(request);
        return request;
    }

    /** 队首完成出队；空队列返回 null */
    public synchronized Request complete() {
        return queue.pollFirst();
    }

    public synchronized int pending() {
        return queue.size();
    }

    public synchronized int capacity() {
        return capacity;
    }
}
