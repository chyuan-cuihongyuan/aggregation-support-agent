package cn.chyuan.ai.domain.natskernel.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 队列组（工单 0947 EB4，nats-server 思想）。
 * 组内轮询均衡/无组全投递/组间互不干扰。
 */
public final class QueueGroups {

    private final Map<String, Integer> cursor = new HashMap<>();

    /** 组内轮询：key 标识一个（模式，组），返回本次选中的成员；空成员拒绝 */
    public String next(String key, List<String> members) {
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("队列组无成员: " + key);
        }
        int index = cursor.merge(key, 1, Integer::sum) - 1;
        return members.get(index % members.size());
    }

    /** 投递折叠：无组订阅全保留；同（模式，组）仅轮询选一名成员；不同组互不干扰 */
    public java.util.List<String> collapse(java.util.List<SubTree.Sub> matched) {
        java.util.List<String> targets = new java.util.ArrayList<>();
        Map<String, Deque<String>> memberByGroup = new HashMap<>();
        java.util.Set<String> served = new java.util.HashSet<>();
        for (SubTree.Sub sub : matched) {
            if (sub.group == null) {
                targets.add(sub.conn);
                continue;
            }
            String key = sub.pattern + "@" + sub.group;
            memberByGroup.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(sub.conn);
        }
        for (SubTree.Sub sub : matched) {
            if (sub.group == null) {
                continue;
            }
            String key = sub.pattern + "@" + sub.group;
            if (served.add(key)) {
                Deque<String> members = memberByGroup.get(key);
                java.util.List<String> list = new java.util.ArrayList<>(members);
                targets.add(next(key, list));
            }
        }
        return targets;
    }
}
