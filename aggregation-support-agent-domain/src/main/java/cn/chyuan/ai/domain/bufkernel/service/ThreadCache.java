package cn.chyuan.ai.domain.bufkernel.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 线程本地分配缓存（工单 1201 FE6，netty PoolThreadCache 思想）。
 * 按「线程×尺寸分级」缓存已释放缓冲；同线程同尺寸分配命中复用；
 * 每级容量上限，溢出最旧一条挤出交还共享池；未命中走共享池。
 */
public final class ThreadCache {

    private final int capacityPerClass;
    private final Map<String, Map<Integer, Deque<Integer>>> caches = new HashMap<>();

    public ThreadCache(int capacityPerClass) {
        if (capacityPerClass <= 0) {
            throw new IllegalArgumentException("缓存容量须为正");
        }
        this.capacityPerClass = capacityPerClass;
    }

    /** 取出：命中返回 bufId，未命中返回 null */
    public Integer take(String thread, int sizeClass) {
        Deque<Integer> queue = queue(thread, sizeClass);
        return queue.isEmpty() ? null : queue.removeLast();
    }

    /** 放回：容量内入缓存；溢出挤出最旧一条交还共享池，返回被挤出者（无挤出返回 null） */
    public Integer give(String thread, int sizeClass, int bufId) {
        Deque<Integer> queue = queue(thread, sizeClass);
        queue.addLast(bufId);
        if (queue.size() > capacityPerClass) {
            return queue.removeFirst();
        }
        return null;
    }

    private Deque<Integer> queue(String thread, int sizeClass) {
        return caches.computeIfAbsent(thread, k -> new HashMap<>())
                .computeIfAbsent(sizeClass, k -> new ArrayDeque<>());
    }
}
